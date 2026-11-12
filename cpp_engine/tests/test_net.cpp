// Loopback test for the socket peer-to-peer connection. No window.
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>

#ifndef _WIN32
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

#include "test_framework.hpp"
#include "../src/net.hpp"

namespace {

using engine::net::Listener;
using engine::net::PeerConnection;
using testutil::check;
using testutil::eqStr;

int freePort() {
    int fd = static_cast<int>(::socket(AF_INET, SOCK_STREAM, 0));
    if (fd < 0) return 0;
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        ::close(fd);
        return 0;
    }
    socklen_t len = sizeof(addr);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len) != 0) {
        ::close(fd);
        return 0;
    }
    int port = ntohs(addr.sin_port);
    ::close(fd);
    return port;
}

void loopbackExchange() {
#ifdef _WIN32
    check(true, "peer: skipped on Windows CI");
    return;
#else
    int port = freePort();
    check(port != 0, "peer: free loopback port");
    if (!port) return;
    std::atomic<int> connected{0};
    std::atomic<bool> moveReceived{false};
    std::atomic<bool> chatReceived{false};
    std::string move, chat;
    std::mutex mtx;
    Listener listener;
    listener.onConnected = [&](const std::string&) { connected.fetch_add(1); };
    listener.onMove = [&](const std::string& n) {
        std::lock_guard<std::mutex> l(mtx);
        move = n;
        moveReceived.store(true);
    };
    listener.onNewGame = [](const std::string&) {};
    listener.onResign = [] {};
    listener.onChat = [&](const std::string& t) {
        std::lock_guard<std::mutex> l(mtx);
        chat = t;
        chatReceived.store(true);
    };
    listener.onDisconnected = [](const std::string&) {};
    PeerConnection host(listener);
    PeerConnection guest(listener);
    host.host(port);
    // Brief pause so the listener is up before the guest dials.
    std::this_thread::sleep_for(std::chrono::milliseconds(150));
    guest.join("127.0.0.1", port);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (connected.load() < 2 && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    check(connected.load() == 2, "peer: host and guest both connect");
    guest.sendMove("e2e4");
    guest.sendChat("hello");
    deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while ((!moveReceived.load() || !chatReceived.load()) &&
           std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    check(moveReceived.load(), "peer: move arrives over the socket");
    {
        std::lock_guard<std::mutex> l(mtx);
        eqStr("e2e4", move, "peer: move notation arrives intact");
    }
    check(chatReceived.load(), "peer: chat arrives over the socket");
    {
        std::lock_guard<std::mutex> l(mtx);
        eqStr("hello", chat, "peer: chat text arrives intact");
    }
    check(host.isConnected() && guest.isConnected(), "peer: both ends report connected");
    host.close();
    guest.close();
#endif
}

}  // namespace

int runNetTests() {
    testutil::reset();
    loopbackExchange();
    return testutil::report("PeerTests");
}
