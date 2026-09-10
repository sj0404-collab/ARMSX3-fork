#include "HttpFile.h"
#include "util/logs.hpp"

#include <cstring>
#include <algorithm>
#include <numeric>
#include <sstream>

#ifdef __ANDROID__
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <arpa/inet.h>
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <arpa/inet.h>
#endif

LOG_CHANNEL(fs_http, "FS HTTP");

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
    // Low-level HTTP helpers (POSIX sockets, no TLS)
    // ============================================================

    static bool parse_url(const std::string& url, std::string& host, int& port, std::string& path)
    {
        // http://host:port/path
        const std::string prefix = "http://";
        if (url.find(prefix) != 0)
            return false;

        std::string rest = url.substr(prefix.size());

        // Split host:port/path
        auto slash_pos = rest.find('/');
        std::string host_port = (slash_pos != std::string::npos) ? rest.substr(0, slash_pos) : rest;
        path = (slash_pos != std::string::npos) ? rest.substr(slash_pos) : "/";

        auto colon_pos = host_port.find(':');
        if (colon_pos != std::string::npos)
        {
            host = host_port.substr(0, colon_pos);
            port = std::stoi(host_port.substr(colon_pos + 1));
        }
        else
        {
            host = host_port;
            port = 80;
        }

        return !host.empty();
    }

    static bool send_all(int fd, const char* buf, size_t len)
    {
        size_t sent = 0;
        while (sent < len)
        {
            ssize_t n = ::send(fd, buf + sent, len - sent, MSG_NOSIGNAL);
            if (n <= 0) return false;
            sent += n;
        }
        return true;
    }

    static bool recv_line(int fd, std::string& line, int timeout_ms = 5000)
    {
        line.clear();
        char c;
        struct pollfd pfd = {fd, POLLIN, 0};
        while (true)
        {
            int pr = ::poll(&pfd, 1, timeout_ms);
            if (pr <= 0) return false;
            ssize_t n = ::recv(fd, &c, 1, 0);
            if (n <= 0) return false;
            if (c == '\n') break;
            if (c != '\r') line += c;
        }
        return true;
    }

    static bool recv_bytes(int fd, void* buf, size_t len, int timeout_ms = 10000)
    {
        size_t got = 0;
        char* ptr = static_cast<char*>(buf);
        struct pollfd pfd = {fd, POLLIN, 0};
        while (got < len)
        {
            int pr = ::poll(&pfd, 1, timeout_ms);
            if (pr <= 0) return false;
            ssize_t n = ::recv(fd, ptr + got, len - got, 0);
            if (n <= 0) return false;
            got += n;
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
        close_connection();
    }

    bool http_file::ensure_connection()
    {
        if (m_sock_fd >= 0) return true;

        std::string host;
        int port;
        std::string path;
        if (!parse_url(m_url, host, port, path))
            return false;

        struct addrinfo hints = {}, *result = nullptr;
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;

        std::string port_str = std::to_string(port);
        int gai = ::getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result);
        if (gai != 0 || !result) return false;

        int fd = ::socket(result->ai_family, result->ai_socktype, result->ai_protocol);
        if (fd < 0) { ::freeaddrinfo(result); return false; }

        // Set TCP_NODELAY for lower latency
        int flag = 1;
        ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

        // Connect with 5s timeout
        ::fcntl(fd, F_SETFL, ::fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
        int connect_result = ::connect(fd, result->ai_addr, result->ai_addrlen);
        ::freeaddrinfo(result);

        if (connect_result < 0 && errno != EINPROGRESS)
        {
            ::close(fd);
            return false;
        }

        if (connect_result < 0)
        {
            struct pollfd pfd = {fd, POLLOUT, 0};
            int pr = ::poll(&pfd, 1, 5000);
            if (pr <= 0 || !(pfd.revents & POLLOUT))
            {
                ::close(fd);
                return false;
            }
            int err = 0;
            socklen_t elen = sizeof(err);
            ::getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &elen);
            if (err != 0)
            {
                ::close(fd);
                return false;
            }
        }

        // Restore blocking
        ::fcntl(fd, F_SETFL, ::fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);

        m_sock_fd = fd;
        return true;
    }

    void http_file::close_connection()
    {
        std::lock_guard lock(m_conn_mutex);
        if (m_sock_fd >= 0)
        {
            ::shutdown(m_sock_fd, SHUT_RDWR);
            ::close(m_sock_fd);
            m_sock_fd = -1;
        }
    }

    bool http_file::http_get_range(u64 offset, u64 length, void* buffer)
    {
        std::lock_guard lock(m_conn_mutex);

        if (!ensure_connection())
            return false;

        std::string host;
        int port;
        std::string path;
        parse_url(m_url, host, port, path);

        // Build HTTP/1.1 Range request
        std::ostringstream req;
        req << "GET " << path << " HTTP/1.1\r\n";
        req << "Host: " << host;
        if (port != 80) req << ":" << port;
        req << "\r\n";
        req << "Range: bytes=" << offset << "-" << (offset + length - 1) << "\r\n";
        req << "Connection: keep-alive\r\n";
        req << "\r\n";

        std::string req_str = req.str();
        if (!send_all(m_sock_fd, req_str.c_str(), req_str.size()))
        {
            close_connection();
            return false;
        }

        // Read status line
        std::string line;
        if (!recv_line(m_sock_fd, line))
        {
            close_connection();
            return false;
        }

        // Check for 200 or 206
        bool ok = false;
        if (line.find("200") != std::string::npos) ok = true;
        if (line.find("206") != std::string::npos) ok = true;
        if (!ok)
        {
            fs_http.error("HTTP %s for Range %llu-%llu", line.c_str(), offset, offset + length - 1);
            close_connection();
            return false;
        }

        // Skip headers until empty line
        u64 content_length = 0;
        while (true)
        {
            if (!recv_line(m_sock_fd, line))
            {
                close_connection();
                return false;
            }
            if (line.empty()) break;

            // Parse Content-Length and Content-Range
            if (line.find("Content-Length:") == 0)
            {
                content_length = std::stoull(line.substr(15));
            }
            if (line.find("Content-Range:") == 0)
            {
                // bytes 0-1048575/5242880000
                auto slash = line.rfind('/');
                if (slash != std::string::npos)
                {
                    content_length = std::stoull(line.substr(slash + 1));
                }
            }
        }

        // Read body
        u64 to_read = std::min(length, content_length > 0 ? content_length : length);
        if (!recv_bytes(m_sock_fd, buffer, to_read))
        {
            close_connection();
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
        std::string host;
        int port;
        std::string path;
        if (!parse_url(url, host, port, path))
            return false;

        struct addrinfo hints = {}, *result = nullptr;
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;

        std::string port_str = std::to_string(port);
        int gai = ::getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result);
        if (gai != 0 || !result) return false;

        int fd = ::socket(result->ai_family, result->ai_socktype, result->ai_protocol);
        if (fd < 0) { ::freeaddrinfo(result); return false; }

        int flag = 1;
        ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

        ::fcntl(fd, F_SETFL, ::fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
        int cr = ::connect(fd, result->ai_addr, result->ai_addrlen);
        ::freeaddrinfo(result);

        if (cr < 0 && errno != EINPROGRESS) { ::close(fd); return false; }
        if (cr < 0)
        {
            struct pollfd pfd = {fd, POLLOUT, 0};
            if (::poll(&pfd, 1, 5000) <= 0) { ::close(fd); return false; }
        }

        ::fcntl(fd, F_SETFL, ::fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);

        std::ostringstream req;
        req << "HEAD " << path << " HTTP/1.1\r\n";
        req << "Host: " << host;
        if (port != 80) req << ":" << port;
        req << "\r\nConnection: close\r\n\r\n";

        std::string req_str = req.str();
        if (!send_all(fd, req_str.c_str(), req_str.size()))
        {
            ::close(fd);
            return false;
        }

        std::string line;
        bool ok = false;
        if (recv_line(fd, line) && (line.find("200") != std::string::npos || line.find("206") != std::string::npos))
            ok = true;

        out_size = 0;
        while (ok && recv_line(fd, line))
        {
            if (line.empty()) break;
            if (line.find("Content-Length:") == 0)
                out_size = std::stoull(line.substr(15));
            if (line.find("Content-Range:") == 0)
            {
                auto slash = line.rfind('/');
                if (slash != std::string::npos)
                    out_size = std::stoull(line.substr(slash + 1));
            }
        }

        ::close(fd);
        return ok && out_size > 0;
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
            fs_http.error("Failed to HEAD %s", path.c_str());
            return nullptr;
        }

        fs_http.success("Opened HTTP file %s (%llu bytes)", path.c_str(), file_size);
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

        set_virtual_device("http_dev", stx::make_shared<http_device>());
        fs_http.success("HTTP file backend registered");
    }
}
