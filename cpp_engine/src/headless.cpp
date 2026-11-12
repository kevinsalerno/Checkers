#include "headless.hpp"

#include <cctype>
#include <fstream>
#include <sstream>
#include <stdexcept>

#include "checkers_engine.hpp"
#include "chess_engine.hpp"

namespace engine {

std::string SimResult::toString() const {
    std::ostringstream sb;
    sb << "GAME: " << gameType << '\n';
    sb << "MOVES_APPLIED: " << movesApplied << '\n';
    sb << "RESULT: " << outcome;
    if (!reason.empty()) sb << " (" << reason << ')';
    sb << '\n';
    if (!error.empty()) sb << "ERROR: " << error << '\n';
    sb << position << '\n';
    return sb.str();
}

static std::string lowerStr(std::string s) {
    for (auto& ch : s) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    return s;
}

SimResult simulate(const std::string& gameType, const std::vector<std::string>& moves) {
    bool isCheckers;
    std::string low = lowerStr(gameType);
    if (low == "checkers") {
        isCheckers = true;
    } else if (low == "chess") {
        isCheckers = false;
    } else {
        throw std::invalid_argument("Unknown game type: " + gameType);
    }
    CheckersEngine checkers;
    ChessEngine chess;
    int applied = 0;
    std::string error;
    bool failed = false;
    for (const auto& mv : moves) {
        try {
            if (isCheckers) {
                checkers.applyNotation(mv);
            } else {
                chess.applyNotation(mv);
            }
            applied++;
        } catch (const std::invalid_argument& e) {
            error = "Move " + std::to_string(applied + 1) + " '" + mv +
                    "' rejected: " + e.what();
            failed = true;
            break;
        }
        if ((isCheckers && checkers.isGameOver()) || (!isCheckers && chess.isGameOver())) break;
    }
    SimResult r;
    r.gameType = isCheckers ? "CHECKERS" : "CHESS";
    r.movesApplied = applied;
    r.success = !failed;
    r.error = error;
    if (isCheckers) {
        r.outcome = checkersOutcomeName(checkers.outcome());
        r.reason = checkers.outcomeReason();
        r.position = checkers.boardDiagram();
    } else {
        r.outcome = chessOutcomeName(chess.outcome());
        r.reason = chess.outcomeReason();
        r.position = chess.boardDiagram() + "\nFEN: " + chess.toFen();
    }
    return r;
}

static void addTokens(std::vector<std::string>& moves, const std::string& text) {
    std::string cur;
    for (char ch : text) {
        if (ch == ',' || std::isspace(static_cast<unsigned char>(ch))) {
            if (!cur.empty()) {
                moves.push_back(cur);
                cur.clear();
            }
        } else {
            cur += ch;
        }
    }
    if (!cur.empty()) moves.push_back(cur);
}

SimResult simulateFile(const std::string& path) {
    std::ifstream in(path);
    if (!in) throw std::invalid_argument("Cannot open simulation file: " + path);
    std::string gameType;
    std::vector<std::string> moves;
    std::string line;
    while (std::getline(in, line)) {
        // Trim (tolerating CRLF).
        size_t a = 0;
        while (a < line.size() && std::isspace(static_cast<unsigned char>(line[a]))) a++;
        size_t b = line.size();
        while (b > a && (std::isspace(static_cast<unsigned char>(line[b - 1])) || line[b - 1] == '\r'))
            b--;
        std::string trimmed = line.substr(a, b - a);
        if (trimmed.empty() || trimmed[0] == '#' ||
            (trimmed.size() >= 2 && trimmed[0] == '/' && trimmed[1] == '/'))
            continue;
        if (gameType.empty()) {
            size_t sp = trimmed.find_first_of(" \t");
            gameType = (sp == std::string::npos) ? trimmed : trimmed.substr(0, sp);
            std::string rest = (sp == std::string::npos) ? "" : trimmed.substr(sp + 1);
            if (!rest.empty()) addTokens(moves, rest);
            continue;
        }
        addTokens(moves, trimmed);
    }
    if (gameType.empty()) throw std::invalid_argument("Empty simulation file: " + path);
    return simulate(gameType, moves);
}

}  // namespace engine
