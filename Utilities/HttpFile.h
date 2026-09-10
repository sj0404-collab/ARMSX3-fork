#pragma once

#include "File.h"
#include <string>
#include <unordered_map>
#include <list>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <functional>

namespace fs
{
    // HTTP file backend: reads remote files via HTTP Range requests.
    // Designed for PS3 disc image streaming from a local VPS/relay.
    //
    // Architecture:
    //   - LRU chunk cache stores recently-read 1MB blocks
    //   - Prefetch thread reads ahead for sequential access patterns
    //   - read_at() serves from cache or blocks on HTTP GET Range
    //   - Thread-safe: multiple emulator threads can read concurrently
    //
    // Usage:
    //   1. Register http_device via set_virtual_device("http_dev", ...)
    //   2. fs::file("http://host/path/to/file.iso") returns an http_file
    //   3. iso_archive reads it through the normal file_base interface
    //
    // Limitations:
    //   - HTTP only (no TLS) — use a local network VPS or plain HTTP server
    //   - Server must support Range requests (most do: nginx, Apache, etc.)
    //   - Random access patterns beyond prefetch window cause stalls

    constexpr u64 HTTP_CHUNK_SIZE = 1 * 1024 * 1024; // 1 MB per chunk
    constexpr u64 HTTP_PREFETCH_CHUNKS = 4;           // read ahead 4 MB
    constexpr u64 HTTP_MAX_CACHE_CHUNKS = 256;        // 256 MB max cache

    struct http_chunk
    {
        u64 offset;        // byte offset in the remote file
        u64 size;          // actual data size (last chunk may be smaller)
        std::vector<u8> data;
    };

    // LRU chunk cache
    class http_chunk_cache
    {
    public:
        http_chunk_cache(u64 max_chunks = HTTP_MAX_CACHE_CHUNKS);
        ~http_chunk_cache() = default;

        // Returns cached chunk if present, nullptr otherwise
        http_chunk* get(u64 chunk_offset);

        // Inserts a chunk into the cache; evicts LRU if full
        void put(std::unique_ptr<http_chunk> chunk);

        // Invalidate all cached data (e.g., on reconnect)
        void clear();

        // Number of currently cached chunks
        u64 size() const;

    private:
        mutable std::mutex m_mutex;
        std::list<u64> m_lru_order; // most recent at front
        std::unordered_map<u64, std::unique_ptr<http_chunk>> m_chunks;
        u64 m_max_chunks;
    };

    // HTTP file: implements file_base for remote files via HTTP Range requests
    class http_file final : public file_base
    {
    public:
        http_file(const std::string& url, u64 file_size);
        ~http_file() override;

        // file_base interface
        bool trunc(u64 length) override;
        u64 read(void* buffer, u64 size) override;
        u64 read_at(u64 offset, void* buffer, u64 size) override;
        u64 write(const void* buffer, u64 size) override;
        u64 seek(s64 offset, seek_mode whence) override;
        u64 size() override;

    private:
        // Fetch a range of bytes from the remote server
        bool http_get_range(u64 offset, u64 length, void* buffer);

        // Fetch a single chunk (1 MB) into the cache
        bool fetch_chunk(u64 chunk_offset);

        // Background prefetch for sequential access
        void prefetch_thread();

        std::string m_url;
        u64 m_file_size;
        u64 m_pos = 0;
        http_chunk_cache m_cache;

        // Prefetch state
        std::thread m_prefetch_thread;
        std::atomic<bool> m_prefetch_stop{false};
        std::atomic<u64> m_last_read_offset{0};
        std::mutex m_prefetch_mutex;
        std::condition_variable m_prefetch_cv;
        bool m_prefetch_notify = false;

        // Connection reuse
        int m_sock_fd = -1;
        std::mutex m_conn_mutex;

        bool ensure_connection();
        void close_connection();
    };

    // HTTP device: virtual device that opens HTTP URLs as http_file instances
    class http_device final : public device_base
    {
    public:
        http_device();
        ~http_device() override = default;

        bool stat(const std::string& path, stat_t& info) override;
        bool statfs(const std::string& path, device_stat& info) override;
        std::unique_ptr<file_base> open(const std::string& path, bs_t<open_mode> mode) override;
        std::unique_ptr<dir_base> open_dir(const std::string& path) override;

        // Probe remote file size via HEAD request
        static bool head_size(const std::string& url, u64& out_size);
    };

    // Check if a path is an HTTP/HTTPS URL
    bool is_http_url(const std::string& path);

    // Initialize the HTTP virtual device (call once at startup)
    void init_http_device();
}
