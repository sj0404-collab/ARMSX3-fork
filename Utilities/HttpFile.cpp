#include "HttpFile.h"
#include "util/logs.hpp"

#include <curl/curl.h>

#include <cstring>
#include <algorithm>
#include <numeric>
#include <string_view>

LOG_CHANNEL(fs_http, "FS HTTP");

namespace
{
    // Write sink: receive a fixed-length body into a caller-owned buffer.
    // Returns 0 when full, which aborts the transfer when the server sends
    // more bytes than the requested range (should not happen with Range).
    struct http_sink
    {
        u8* buffer;
        u64 capacity;
        u64 written = 0;
    };

    size_t http_write_cb(void* data, size_t size, size_t nmemb, void* userp)
    {
        const size_t n = size * nmemb;
        auto* sink = static_cast<http_sink*>(userp);

        if (sink->written + n > sink->capacity)
            return 0;

        std::memcpy(sink->buffer + sink->written, data, n);
        sink->written += n;
        return n;
    }

    // Header sink: capture Content-Length (and the total from Content-Range
    // when the server only reports that, e.g. in response to a ranged GET).
    struct http_header_sink
    {
        u64 content_length = 0;
    };

    size_t http_header_cb(char* data, size_t size, size_t nmemb, void* userp)
    {
        const size_t n = size * nmemb;
        auto* headers = static_cast<http_header_sink*>(userp);

        std::string_view line(data, n);
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n'))
            line.remove_suffix(1);

        if (line.find("Content-Length:") == 0)
        {
            headers->content_length = std::strtoull(line.data() + 15, nullptr, 10);
        }
        else if (line.find("Content-Range:") == 0)
        {
            // e.g. "bytes 0-1048575/5242880000"
            const auto slash = line.rfind('/');
            if (slash != std::string_view::npos)
            {
                headers->content_length = std::strtoull(line.data() + slash + 1, nullptr, 10);
            }
        }

        return n;
    }

    // Split "[user[:password]@]" out of scheme://user:pass@host/path, leaving
    // url without credentials (libcurl would otherwise log them in errors).
    void split_userinfo(std::string& url, std::string* user, std::string* pass)
    {
        const auto scheme_end = url.find("://");
        if (scheme_end == std::string::npos)
            return;

        const auto at = url.find('@', scheme_end + 3);
        if (at == std::string::npos)
            return;

        std::string_view userinfo(url.data() + scheme_end + 3, at - (scheme_end + 3));
        const auto colon = userinfo.find(':');

        if (colon == std::string_view::npos)
        {
            if (user) *user = std::string(userinfo);
        }
        else
        {
            if (user) *user = std::string(userinfo.substr(0, colon));
            if (pass) *pass = std::string(userinfo.substr(colon + 1));
        }

        url.erase(scheme_end + 3, at - (scheme_end + 3) + 1);
    }

    // Replace the password in "scheme://user:pass@host/path" with "***" for
    // log output, so credentials never reach the log.
    std::string redact_url(const std::string& url)
    {
        std::string copy = url;

        const auto scheme_end = copy.find("://");
        if (scheme_end == std::string::npos)
            return copy;

        const auto at = copy.find('@', scheme_end + 3);
        if (at == std::string::npos)
            return copy;

        const auto colon = copy.find(':', scheme_end + 3);
        if (colon == std::string::npos || colon > at)
            return copy;

        copy.replace(colon + 1, at - (colon + 1), "***");
        return copy;
    }
}

namespace fs
{
    // ============================================================
    // http_chunk_cache
    // ============================================================

    http_chunk_cache::http_chunk_cache(u64 max_chunks)
        : m_max_chunks(max_chunks)
    {
    }

    http_chunk* http_chunk_cache::get(u64 chunk_offset)
    {
        std::lock_guard lock(m_mutex);
        auto it = m_chunks.find(chunk_offset);
        if (it == m_chunks.end())
            return nullptr;

        // Move to front of LRU
        m_lru_order.remove(chunk_offset);
        m_lru_order.push_front(chunk_offset);

        return it->second.get();
    }

    void http_chunk_cache::put(std::unique_ptr<http_chunk> chunk)
    {
        std::lock_guard lock(m_mutex);
        u64 offset = chunk->offset;

        // If already cached, update
        if (m_chunks.count(offset))
        {
            m_chunks[offset] = std::move(chunk);
            m_lru_order.remove(offset);
            m_lru_order.push_front(offset);
            return;
        }

        // Evict LRU if full
        while (m_chunks.size() >= m_max_chunks && !m_lru_order.empty())
        {
            u64 evict = m_lru_order.back();
            m_lru_order.pop_back();
            m_chunks.erase(evict);
        }

        m_lru_order.push_front(offset);
        m_chunks[offset] = std::move(chunk);
    }

    void http_chunk_cache::clear()
    {
        std::lock_guard lock(m_mutex);
        m_chunks.clear();
        m_lru_order.clear();
    }

    u64 http_chunk_cache::size() const
    {
        std::lock_guard lock(m_mutex);
        return m_chunks.size();
    }

    // ============================================================
    // libcurl transport
    // ============================================================

    curl_handle::~curl_handle()
    {
        close();
    }

    void curl_handle::close()
    {
        if (m_headers)
        {
            curl_slist_free_all(m_headers);
            m_headers = nullptr;
        }

        if (m_curl)
        {
            curl_easy_cleanup(m_curl);
            m_curl = nullptr;
        }
    }

    bool curl_handle::setup(const std::string& url)
    {
        close();

        std::string req_url = url;
        std::string user;
        std::string pass;
        split_userinfo(req_url, &user, &pass);

        m_curl = curl_easy_init();
        if (!m_curl)
        {
            fs_http.error("curl_easy_init failed");
            return false;
        }

        curl_easy_setopt(m_curl, CURLOPT_URL, req_url.c_str());
        curl_easy_setopt(m_curl, CURLOPT_FOLLOWLOCATION, 1L);
        curl_easy_setopt(m_curl, CURLOPT_MAXREDIRS, 5L);
        curl_easy_setopt(m_curl, CURLOPT_CONNECTTIMEOUT, 10L);
        curl_easy_setopt(m_curl, CURLOPT_LOW_SPEED_LIMIT, 1L);
        curl_easy_setopt(m_curl, CURLOPT_LOW_SPEED_TIME, 30L);
        curl_easy_setopt(m_curl, CURLOPT_NOSIGNAL, 1L);
        curl_easy_setopt(m_curl, CURLOPT_USERAGENT, "ARMSX3-Core/1.0");
        curl_easy_setopt(m_curl, CURLOPT_ACCEPT_ENCODING, nullptr); // identity only

#ifdef __ANDROID__
        // Use the platform CA store on Android (curl's own bundle does not exist there).
        curl_easy_setopt(m_curl, CURLOPT_SSL_OPTIONS, CURLSSLOPT_NATIVE_CA);
#endif

        if (!user.empty())
        {
            // user:pass@ credentials become HTTP Basic auth. Passwords live in
            // the URL the app constructs; libcurl sends them only to this host.
            curl_easy_setopt(m_curl, CURLOPT_HTTPAUTH, CURLAUTH_BASIC);
            curl_easy_setopt(m_curl, CURLOPT_USERNAME, user.c_str());
            curl_easy_setopt(m_curl, CURLOPT_PASSWORD, pass.c_str());
        }

        m_headers = curl_slist_append(nullptr, "Accept: */*");
        if (m_headers)
        {
            curl_easy_setopt(m_curl, CURLOPT_HTTPHEADER, m_headers);
        }

        return true;
    }

    // ============================================================
    // http_file
    // ============================================================

    http_file::http_file(const std::string& url, u64 file_size)
        : m_url(url)
        , m_file_size(file_size)
    {
        // Start prefetch thread
        m_prefetch_thread = std::thread(&http_file::prefetch_thread, this);
    }

    http_file::~http_file()
    {
        m_prefetch_stop = true;
        {
            std::lock_guard lock(m_prefetch_mutex);
            m_prefetch_notify = true;
        }
        m_prefetch_cv.notify_all();
        if (m_prefetch_thread.joinable())
            m_prefetch_thread.join();

        std::lock_guard lock(m_conn_mutex);
        m_curl.close();
    }

    bool http_file::http_get_range(u64 offset, u64 length, void* buffer)
    {
        std::lock_guard lock(m_conn_mutex);

        if (!m_curl.get())
        {
            if (!m_curl.setup(m_url))
            {
                fs_http.error("Failed to init curl handle for %s", redact_url(m_url).c_str());
                return false;
            }
        }

        const std::string range = fmt::format("bytes=%llu-%llu", offset, offset + length - 1);
        curl_easy_setopt(m_curl.get(), CURLOPT_RANGE, range.c_str());
        curl_easy_setopt(m_curl.get(), CURLOPT_HTTPGET, 1L);

        http_sink sink{static_cast<u8*>(buffer), length};
        curl_easy_setopt(m_curl.get(), CURLOPT_WRITEFUNCTION, http_write_cb);
        curl_easy_setopt(m_curl.get(), CURLOPT_WRITEDATA, &sink);

        char errbuf[CURL_ERROR_SIZE] = {};
        curl_easy_setopt(m_curl.get(), CURLOPT_ERRORBUFFER, errbuf);

        const CURLcode err = curl_easy_perform(m_curl.get());
        if (err != CURLE_OK)
        {
            fs_http.error("Range GET %llu-%llu failed: %s (%s)", offset, offset + length - 1,
                          curl_easy_strerror(err), errbuf);
            m_curl.close();
            return false;
        }

        long status = 0;
        curl_easy_getinfo(m_curl.get(), CURLINFO_RESPONSE_CODE, &status);
        if (status != 200 && status != 206)
        {
            fs_http.error("Range GET %llu-%llu: HTTP %ld", offset, offset + length - 1, status);
            m_curl.close();
            return false;
        }

        if (sink.written != length)
        {
            fs_http.error("Range GET %llu-%llu: short read (%llu/%llu)",
                          offset, offset + length - 1, sink.written, length);
            m_curl.close();
            return false;
        }

        return true;
    }

    bool http_file::fetch_chunk(u64 chunk_offset)
    {
        if (m_cache.get(chunk_offset))
            return true; // already cached

        u64 chunk_len = std::min(HTTP_CHUNK_SIZE, m_file_size - chunk_offset);
        if (chunk_len == 0) return true;

        auto chunk = std::make_unique<http_chunk>();
        chunk->offset = chunk_offset;
        chunk->size = chunk_len;
        chunk->data.resize(chunk_len);

        if (!http_get_range(chunk_offset, chunk_len, chunk->data.data()))
        {
            fs_http.error("Failed to fetch chunk at offset %llu", chunk_offset);
            return false;
        }

        m_cache.put(std::move(chunk));
        return true;
    }

    void http_file::prefetch_thread()
    {
        while (!m_prefetch_stop)
        {
            u64 last = m_last_read_offset.load();

            // Prefetch ahead of last read position
            for (u64 i = 0; i < HTTP_PREFETCH_CHUNKS; i++)
            {
                if (m_prefetch_stop) return;
                u64 prefetch_offset = ((last / HTTP_CHUNK_SIZE) + i + 1) * HTTP_CHUNK_SIZE;
                if (prefetch_offset >= m_file_size) break;
                fetch_chunk(prefetch_offset);
            }

            // Wait for next read notification
            std::unique_lock lock(m_prefetch_mutex);
            m_prefetch_cv.wait_for(lock, std::chrono::milliseconds(200), [this] {
                return m_prefetch_notify || m_prefetch_stop.load();
            });
            m_prefetch_notify = false;
        }
    }

    u64 http_file::read(void* buffer, u64 count)
    {
        const auto r = read_at(m_pos, buffer, count);
        m_pos += r;
        return r;
    }

    u64 http_file::read_at(u64 offset, void* buffer, u64 count)
    {
        if (offset >= m_file_size) return 0;
        count = std::min(count, m_file_size - offset);
        if (count == 0) return 0;

        u64 total_read = 0;
        u8* ptr = static_cast<u8*>(buffer);

        while (total_read < count)
        {
            u64 chunk_offset = ((offset + total_read) / HTTP_CHUNK_SIZE) * HTTP_CHUNK_SIZE;
            u64 chunk_start = (offset + total_read) - chunk_offset;
            u64 remaining = count - total_read;
            u64 chunk_avail = std::min(HTTP_CHUNK_SIZE - chunk_start, remaining);

            http_chunk* cached = m_cache.get(chunk_offset);
            if (!cached)
            {
                // Cache miss — fetch synchronously
                if (!fetch_chunk(chunk_offset))
                    break;
                cached = m_cache.get(chunk_offset);
                if (!cached) break;
            }

            // Copy from cache
            u64 copy_size = std::min(chunk_avail, cached->size - chunk_start);
            std::memcpy(ptr + total_read, cached->data.data() + chunk_start, copy_size);
            total_read += copy_size;

            // Notify prefetch thread
            m_last_read_offset.store(offset + total_read);
            {
                std::lock_guard lock(m_prefetch_mutex);
                m_prefetch_notify = true;
            }
            m_prefetch_cv.notify_all();
        }

        return total_read;
    }

    bool http_file::trunc(u64)
    {
        return false; // read-only
    }

    u64 http_file::write(const void*, u64)
    {
        return 0; // read-only
    }

    u64 http_file::seek(s64 offset, seek_mode whence)
    {
        switch (whence)
        {
        case seek_set: m_pos = offset; break;
        case seek_cur: m_pos += offset; break;
        case seek_end: m_pos = m_file_size + offset; break;
        }
        return m_pos;
    }

    u64 http_file::size()
    {
        return m_file_size;
    }

    // ============================================================
    // http_device
    // ============================================================

    http_device::http_device()
    {
        fs_prefix = "/http_dev";
    }

    bool http_device::head_size(const std::string& url, u64& out_size)
    {
        out_size = 0;

        // Try a HEAD request first: the cheapest way to learn Content-Length.
        {
            curl_handle ch;
            if (!ch.setup(url))
                return false;

            http_header_sink headers;
            http_sink discard{nullptr, 0};

            curl_easy_setopt(ch.get(), CURLOPT_NOBODY, 1L);
            curl_easy_setopt(ch.get(), CURLOPT_HEADERFUNCTION, http_header_cb);
            curl_easy_setopt(ch.get(), CURLOPT_HEADERDATA, &headers);
            curl_easy_setopt(ch.get(), CURLOPT_WRITEFUNCTION, http_write_cb);
            curl_easy_setopt(ch.get(), CURLOPT_WRITEDATA, &discard);

            char errbuf[CURL_ERROR_SIZE] = {};
            curl_easy_setopt(ch.get(), CURLOPT_ERRORBUFFER, errbuf);

            const CURLcode err = curl_easy_perform(ch.get());
            if (err == CURLE_OK)
            {
                long status = 0;
                curl_easy_getinfo(ch.get(), CURLINFO_RESPONSE_CODE, &status);
                if (status >= 200 && status < 300 && headers.content_length > 0)
                {
                    out_size = headers.content_length;
                    return true;
                }
            }
            else
            {
                fs_http.notice("HEAD %s failed: %s (falling back to ranged GET)", redact_url(url).c_str(),
                               curl_easy_strerror(err));
            }
        }

        // Some servers refuse HEAD (or return no Content-Length for it). Probe
        // with a 1-byte ranged GET instead: the total size comes back in
        // Content-Range: bytes 0-0/N. Range support is mandatory anyway, so
        // this also doubles as the can-this-file-be-streamed check.
        curl_handle ch;
        if (!ch.setup(url))
            return false;

        u8 probe_byte = 0;
        http_sink sink{&probe_byte, 1};
        http_header_sink headers;

        curl_easy_setopt(ch.get(), CURLOPT_RANGE, "bytes=0-0");
        curl_easy_setopt(ch.get(), CURLOPT_HTTPGET, 1L);
        curl_easy_setopt(ch.get(), CURLOPT_HEADERFUNCTION, http_header_cb);
        curl_easy_setopt(ch.get(), CURLOPT_HEADERDATA, &headers);
        curl_easy_setopt(ch.get(), CURLOPT_WRITEFUNCTION, http_write_cb);
        curl_easy_setopt(ch.get(), CURLOPT_WRITEDATA, &sink);

        char errbuf[CURL_ERROR_SIZE] = {};
        curl_easy_setopt(ch.get(), CURLOPT_ERRORBUFFER, errbuf);

        const CURLcode get_err = curl_easy_perform(ch.get());
        if (get_err != CURLE_OK)
        {
            fs_http.error("Probe GET %s: %s (%s)", redact_url(url).c_str(), curl_easy_strerror(get_err), errbuf);
            return false;
        }

        long status = 0;
        curl_easy_getinfo(ch.get(), CURLINFO_RESPONSE_CODE, &status);
        if (status != 200 && status != 206)
        {
            fs_http.error("Probe GET %s: HTTP %ld", redact_url(url).c_str(), status);
            return false;
        }

        if (headers.content_length == 0)
        {
            fs_http.error("Probe GET %s: no Content-Range/Length in response", redact_url(url).c_str());
            return false;
        }

        out_size = headers.content_length;
        return true;
    }

    bool http_device::stat(const std::string& path, stat_t& info)
    {
        u64 file_size = 0;
        if (!head_size(path, file_size))
            return false;

        info.is_directory = false;
        info.is_writable = false;
        info.size = file_size;
        info.atime = 0;
        info.mtime = 0;
        info.ctime = 0;
        return true;
    }

    bool http_device::statfs(const std::string&, device_stat& info)
    {
        info.block_size = HTTP_CHUNK_SIZE;
        info.total_size = 0;
        info.total_free = 0;
        info.avail_free = 0;
        return true;
    }

    std::unique_ptr<file_base> http_device::open(const std::string& path, bs_t<open_mode> mode)
    {
        u64 file_size = 0;
        if (!head_size(path, file_size))
        {
            fs_http.error("Failed to probe %s", redact_url(path).c_str());
            return nullptr;
        }

        fs_http.success("Opened HTTP file %s (%llu bytes)", redact_url(path).c_str(), file_size);
        return std::make_unique<http_file>(path, file_size);
    }

    std::unique_ptr<dir_base> http_device::open_dir(const std::string&)
    {
        return nullptr; // HTTP device doesn't support directory listing
    }

    // ============================================================
    // URL detection and initialization
    // ============================================================

    bool is_http_url(const std::string& path)
    {
        return path.starts_with("http://") || path.starts_with("https://");
    }

    void init_http_device()
    {
        static bool initialized = false;
        if (initialized) return;
        initialized = true;

        // curl_global_init is not thread-safe; the guarded first call runs
        // before any discovered http_file exists, so no race in practice.
        if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK)
        {
            fs_http.error("curl_global_init failed; HTTP file backend disabled");
            return;
        }

        set_virtual_device("http_dev", stx::make_shared<http_device>());
        fs_http.success("HTTP file backend registered");
    }
}