// Peer-to-peer multiplayer over plain TCP sockets (POSIX / Winsock).
//
// Line protocol (UTF-8, one message per line):
// - "HELLO <mode>" - sent on connect, announces the game mode.
// - "MOVE <notation>" - one applied move; notation is the same
//   coordinate/SAN text the headless harness accepts, except a
//   checkers multi-jump chain is sent as one MOVE per step so both
//   peers stay in sync through a chain.
// - "NEW <mode>" - start a new game of the given mode.
// - "RESIGN" - the sender resigns.
// - "CHAT <text>" - chat text (single line).
//
// NOTE on the task's WebRTC wording (mirroring the Java port's honest
// note): a real WebRTC stack (ICE, DTLS-SRTP, SCTP) cannot be built from
// the C++ standard library alone without external libraries. This
// deliverable therefore uses direct socket peer-to-peer: two peers
// connect directly and exchange the same move messages a WebRTC data
// channel would carry, with no gameplay relay server. Play across
// networks by hosting on a reachable address/port (LAN address or
// forwarded port). The host plays the first-moving side (Black in
// checkers, White in chess); the guest takes the other side.
#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace engine {
namespace net {

struct Listener {
    std::function<void(const std::string& remote)> onConnected;
    std::function<void(const std::string& notation)> onMove;
    std::function<void(const std::string& mode)> onNewGame;
    std::function<void()> onResign;
    std::function<void(const std::string& text)> onChat;
    std::function<void(const std::string& reason)> onDisconnected;
};

class PeerConnection {
public:
    explicit PeerConnection(Listener listener) : listener_(std::move(listener)) {}
    ~PeerConnection() { close(); }

    PeerConnection(const PeerConnection&) = delete;
    PeerConnection& operator=(const PeerConnection&) = delete;

    bool isConnected() const;

    // Listens on `port` and accepts the first peer (background thread).
    void host(int port);
    // Connects to a peer already hosting on host:port (background thread).
    void join(const std::string& host, int port);

    void send(const std::string& line);
    void sendHello(const std::string& mode) { send("HELLO " + mode); }
    void sendMove(const std::string& notation) { send("MOVE " + notation); }
    void sendNewGame(const std::string& mode) { send("NEW " + mode); }
    void sendResign() { send("RESIGN"); }
    void sendChat(const std::string& text);

    void close();

private:
    void attachConnected(int fd, const std::string& remoteDesc);
    void readLoop();

    Listener listener_;
    mutable std::mutex mutex_;
    int sockfd_ = -1;
    int acceptFd_ = -1;  // listening socket (host mode)
    std::atomic<bool> closed_{false};
    std::thread reader_;
    std::mutex sendMutex_;
};

}  // namespace net
}  // namespace engine
