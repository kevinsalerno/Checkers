#include "net.hpp"

#include <cstring>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
using socklen_t = int;
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace engine {
namespace net {

namespace {

#ifdef _WIN32
struct WinsockInit {
    WinsockInit() {
        WSADATA wsa;
        WSAStartup(MAKEWORD(2, 2), &wsa);
    }
    ~WinsockInit() { WSACleanup(); }
};
void ensureNetwork() {
    static WinsockInit init;
}
int closeFd(int fd) { return closesocket(fd); }
#else
void ensureNetwork() {}
int closeFd(int fd) { return ::close(fd); }
#endif

bool sendAll(int fd, const char* data, size_t n) {
    size_t sent = 0;
    while (sent < n) {
#ifdef _WIN32
        int r = ::send(fd, data + sent, static_cast<int>(n - sent), 0);
#else
        ssize_t r = ::send(fd, data + sent, n - sent, 0);
#endif
        if (r <= 0) return false;
        sent += static_cast<size_t>(r);
    }
    return true;
}

std::string peerDesc(int fd) {
    sockaddr_storage addr{};
    socklen_t len = sizeof(addr);
    if (getpeername(fd, reinterpret_cast<sockaddr*>(&addr), &len) != 0) return "peer";
    char host[NI_MAXHOST] = {0}, port[NI_MAXSERV] = {0};
    if (getnameinfo(reinterpret_cast<sockaddr*>(&addr), len, host, sizeof(host), port,
                    sizeof(port), NI_NUMERICHOST | NI_NUMERICSERV) != 0)
        return "peer";
    return std::string(host) + ":" + port;
}

}  // namespace

bool PeerConnection::isConnected() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sockfd_ >= 0 && !closed_.load();
}

void PeerConnection::host(int port) {
    ensureNetwork();
    std::thread([this, port] {
        int listenFd = static_cast<int>(::socket(AF_INET, SOCK_STREAM, 0));
        if (listenFd < 0) {
            if (listener_.onDisconnected) listener_.onDisconnected("Host failed: socket error");
            return;
        }
        int one = 1;
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&one),
                   sizeof(one));
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = htons(static_cast<uint16_t>(port));
        if (bind(listenFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
            listen(listenFd, 1) != 0) {
            closeFd(listenFd);
            if (!closed_.load() && listener_.onDisconnected)
                listener_.onDisconnected("Host failed: bind/listen error");
            return;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (closed_.load()) {
                closeFd(listenFd);
                return;
            }
            acceptFd_ = listenFd;
        }
        int accepted = static_cast<int>(accept(listenFd, nullptr, nullptr));
        closeFd(listenFd);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            acceptFd_ = -1;
        }
        if (accepted < 0) {
            if (!closed_.load() && listener_.onDisconnected)
                listener_.onDisconnected("Host failed: accept error");
            return;
        }
        if (closed_.load()) {
            closeFd(accepted);
            return;
        }
        attachConnected(accepted, peerDesc(accepted));
    }).detach();
}

void PeerConnection::join(const std::string& host, int port) {
    ensureNetwork();
    std::thread([this, host, port] {
        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* list = nullptr;
        if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &list) != 0) {
            if (listener_.onDisconnected) listener_.onDisconnected("Connect failed: DNS error");
            return;
        }
        int fd = -1;
        for (addrinfo* ai = list; ai; ai = ai->ai_next) {
            fd = static_cast<int>(::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol));
            if (fd < 0) continue;
            if (connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
            closeFd(fd);
            fd = -1;
        }
        freeaddrinfo(list);
        if (fd < 0) {
            if (listener_.onDisconnected)
                listener_.onDisconnected("Connect failed: connection refused");
            return;
        }
        if (closed_.load()) {
            closeFd(fd);
            return;
        }
        attachConnected(fd, peerDesc(fd));
    }).detach();
}

void PeerConnection::attachConnected(int fd, const std::string& remoteDesc) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_.load()) {
            closeFd(fd);
            return;
        }
        if (sockfd_ >= 0) closeFd(sockfd_);
        sockfd_ = fd;
    }
    if (listener_.onConnected) {
        try {
            listener_.onConnected(remoteDesc);
        } catch (...) {
        }
    }
    reader_ = std::thread([this] { readLoop(); });
    reader_.detach();
}

void PeerConnection::readLoop() {
    std::string buffer;
    buffer.reserve(256);
    char chunk[512];
    for (;;) {
        int fd;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            fd = sockfd_;
        }
        if (fd < 0 || closed_.load()) break;
#ifdef _WIN32
        int r = recv(fd, chunk, sizeof(chunk), 0);
#else
        ssize_t r = recv(fd, chunk, sizeof(chunk), 0);
#endif
        if (r <= 0) {
            if (!closed_.load() && listener_.onDisconnected) {
                try {
                    listener_.onDisconnected("Peer closed the connection");
                } catch (...) {
                }
            }
            break;
        }
        buffer.append(chunk, static_cast<size_t>(r));
        for (;;) {
            size_t nl = buffer.find('\n');
            if (nl == std::string::npos) break;
            std::string line = buffer.substr(0, nl);
            buffer.erase(0, nl + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            try {
                if (line.compare(0, 5, "MOVE ") == 0 && listener_.onMove) {
                    listener_.onMove(line.substr(5));
                } else if (line.compare(0, 4, "NEW ") == 0 && listener_.onNewGame) {
                    listener_.onNewGame(line.substr(4));
                } else if (line == "RESIGN" && listener_.onResign) {
                    listener_.onResign();
                } else if (line.compare(0, 5, "CHAT ") == 0 && listener_.onChat) {
                    listener_.onChat(line.substr(5));
                }
                // HELLO and unknown lines are ignored.
            } catch (...) {
            }
        }
    }
}

void PeerConnection::send(const std::string& line) {
    std::lock_guard<std::mutex> lock(sendMutex_);
    int fd;
    {
        std::lock_guard<std::mutex> lock2(mutex_);
        fd = sockfd_;
    }
    if (fd < 0) return;
    std::string out = line + "\n";
    sendAll(fd, out.data(), out.size());
}

void PeerConnection::sendChat(const std::string& text) {
    std::string clean;
    for (char ch : text)
        clean += (ch == '\n' || ch == '\r') ? ' ' : ch;
    send("CHAT " + clean);
}

void PeerConnection::close() {
    bool expected = false;
    if (!closed_.compare_exchange_strong(expected, true)) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (sockfd_ >= 0) {
        closeFd(sockfd_);
        sockfd_ = -1;
    }
    if (acceptFd_ >= 0) {
        closeFd(acceptFd_);
        acceptFd_ = -1;
    }
}

}  // namespace net
}  // namespace engine
