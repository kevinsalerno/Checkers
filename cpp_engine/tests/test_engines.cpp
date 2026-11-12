// Headless tests for the checkers and chess engines and the game
// controller. Mirrors the Java EngineTests suite (the Board/BoardObject
// facade it covers is a Java-legacy wrapper; the C++ port exercises the
// engines and GameController directly instead).
#include <cmath>
#include <map>
#include <random>
#include <set>
#include <string>
#include <tuple>
#include <vector>

#include "test_framework.hpp"
#include "../src/checkers_engine.hpp"
#include "../src/chess_engine.hpp"
#include "../src/chess_move.hpp"
#include "../src/game_controller.hpp"

namespace {

using engine::CheckersEngine;
using engine::CheckersMove;
using engine::ChessEngine;
using engine::ChessMove;
using engine::ChessPiece;
using engine::GameController;
using engine::Side;
using testutil::check;
using testutil::eqDouble;
using testutil::eqInt;
using testutil::eqStr;
using testutil::rejects;

void checkersInitial() {
    CheckersEngine e;
    eqInt(12, e.countPieces(Side::White), "checkers: 12 white pieces");
    eqInt(12, e.countPieces(Side::Black), "checkers: 12 black pieces");
    check(e.sideToMove() == Side::Black, "checkers: black moves first");
    eqInt(7, (int)e.legalMoves().size(), "checkers: 7 opening moves");
    check(!e.isGameOver(), "checkers: opening is in progress");
}

CheckersEngine customCheckers(std::vector<std::vector<int>> pieces, Side toMove) {
    CheckersEngine e;
    e.clearBoard();
    for (auto& p : pieces) e.setPiece(p[0], p[1], p[2]);
    e.setSideToMove(toMove);
    return e;
}

void checkersMenAndCaptures() {
    CheckersEngine e = customCheckers(
        {{4, 3, CheckersEngine::WhiteMan}, {1, 1, CheckersEngine::BlackMan}}, Side::White);
    std::set<std::string> moves;
    for (auto& m : e.legalMoves()) moves.insert(m.toString());
    check(moves.count("d4-c5"), "checkers: man steps forward left");
    check(moves.count("d4-e5"), "checkers: man steps forward right");
    check(!moves.count("d4-c3") && !moves.count("d4-e3"), "checkers: man never steps backward");
    CheckersEngine k = customCheckers(
        {{4, 3, CheckersEngine::WhiteKing}, {1, 1, CheckersEngine::BlackMan}}, Side::White);
    std::set<std::string> kmoves;
    for (auto& m : k.legalMoves()) kmoves.insert(m.toString());
    check(kmoves.size() == 4, "checkers: king has 4 steps");
    CheckersEngine c = customCheckers({{4, 3, CheckersEngine::WhiteMan},
                                       {3, 2, CheckersEngine::BlackMan},
                                       {5, 0, CheckersEngine::WhiteMan},
                                       {0, 0, CheckersEngine::BlackMan}},
                                      Side::White);
    for (auto& m : c.legalMoves())
        check(m.isJump(), "checkers: only jumps legal when a capture exists");
    eqInt(1, (int)c.legalMoves().size(), "checkers: exactly one capture");
    rejects([&] { c.applyNotation("a3-b4"); },
            "checkers: quiet move rejected while capture is mandatory");
}

void checkersChainsAndCrowning() {
    CheckersEngine e = customCheckers({{5, 2, CheckersEngine::WhiteMan},
                                       {4, 3, CheckersEngine::BlackMan},
                                       {2, 5, CheckersEngine::BlackMan},
                                       {0, 1, CheckersEngine::BlackMan}},
                                      Side::White);
    CheckersMove first = e.parseSingleStep("c3xe5");
    e.applyMove(first);
    check(e.isChainActive(), "checkers: chain stays open after first jump");
    check(e.sideToMove() == Side::White, "checkers: turn does not pass mid-chain");
    eqInt(1, (int)e.legalMoves().size(), "checkers: only the chaining piece may move");
    eqInt(CheckersEngine::Empty, e.pieceAt(4, 3), "checkers: first victim removed");
    e.applyMove(e.parseSingleStep("e5xg7"));
    check(!e.isChainActive(), "checkers: chain ends after last jump");
    check(e.sideToMove() == Side::Black, "checkers: turn passes after chain");
    eqInt(CheckersEngine::Empty, e.pieceAt(2, 5), "checkers: second victim removed");
    eqInt(1, e.countPieces(Side::Black), "checkers: one black piece left");

    CheckersEngine e2 = customCheckers({{5, 2, CheckersEngine::WhiteMan},
                                        {4, 3, CheckersEngine::BlackMan},
                                        {2, 5, CheckersEngine::BlackMan},
                                        {0, 1, CheckersEngine::BlackMan}},
                                       Side::White);
    rejects([&] { e2.applyNotation("c3xe5"); }, "checkers: incomplete multi-jump rejected");
    eqInt(CheckersEngine::WhiteMan, e2.pieceAt(5, 2),
          "checkers: rejected chain leaves position unchanged");
    eqInt(CheckersEngine::BlackMan, e2.pieceAt(4, 3), "checkers: rejected chain keeps victims");
    e2.applyNotation("c3xe5xg7");
    eqInt(CheckersEngine::WhiteMan, e2.pieceAt(1, 6),
          "checkers: full chain notation lands on g7");

    CheckersEngine crown = customCheckers({{1, 2, CheckersEngine::WhiteMan},
                                           {1, 4, CheckersEngine::BlackMan},
                                           {6, 6, CheckersEngine::BlackMan}},
                                          Side::White);
    crown.applyNotation("c7-d8");
    eqInt(CheckersEngine::WhiteKing, crown.pieceAt(0, 3),
          "checkers: reaching the far row crowns the man");
    check(crown.sideToMove() == Side::Black,
          "checkers: crowning ends the turn despite a king jump");
    eqInt(CheckersEngine::BlackMan, crown.pieceAt(1, 4),
          "checkers: crowning does not continue into a king jump");

    CheckersEngine fly = customCheckers({{4, 3, CheckersEngine::WhiteKing},
                                         {2, 1, CheckersEngine::BlackMan},
                                         {7, 7, CheckersEngine::BlackMan}},
                                        Side::White);
    bool distantJump = false;
    for (auto& m : fly.legalMoves())
        if (std::abs(m.toRow - m.fromRow) > 2) distantJump = true;
    check(!distantJump, "checkers: kings are non-flying (jumps span 2)");
}

bool kingsDiagonallyAdjacent(const CheckersEngine& e) {
    int wr = -1, wc = -1, br = -1, bc = -1;
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            if (e.pieceAt(r, c) == CheckersEngine::WhiteKing) {
                wr = r;
                wc = c;
            }
            if (e.pieceAt(r, c) == CheckersEngine::BlackKing) {
                br = r;
                bc = c;
            }
        }
    return std::abs(wr - br) == 1 && std::abs(wc - bc) == 1;
}

void checkersEndgames() {
    CheckersEngine win = customCheckers(
        {{4, 3, CheckersEngine::WhiteKing}, {3, 2, CheckersEngine::BlackMan}}, Side::White);
    win.applyNotation("d4xb6");
    check(win.outcome() == CheckersEngine::Outcome::WhiteWins,
          "checkers: capturing the last piece wins");
    CheckersEngine blocked = customCheckers({{0, 1, CheckersEngine::BlackMan},
                                             {1, 0, CheckersEngine::WhiteMan},
                                             {1, 2, CheckersEngine::WhiteMan},
                                             {2, 3, CheckersEngine::WhiteMan},
                                             {6, 6, CheckersEngine::WhiteKing}},
                                            Side::Black);
    check(blocked.outcome() == CheckersEngine::Outcome::WhiteWins,
          "checkers: side with no legal move loses");
    CheckersEngine rep = customCheckers({{3, 2, CheckersEngine::WhiteKing},
                                         {4, 5, CheckersEngine::BlackKing}},
                                        Side::White);
    for (auto s : {"c5-d6", "f4-e3", "d6-c5", "e3-f4", "c5-d6", "f4-e3", "d6-c5", "e3-f4"})
        rep.applyNotation(s);
    check(rep.outcome() == CheckersEngine::Outcome::Draw,
          "checkers: threefold repetition is a draw");
    check(rep.outcomeReason().find("repetition") != std::string::npos,
          "checkers: repetition reason reported");
    // 40-move rule walk (same construction as the Java suite).
    bool haveLong = false;
    CheckersEngine longGame = customCheckers({{0, 0, CheckersEngine::BlackMan}},
                                             Side::White);  // placeholder
    for (int attempt = 0; attempt < 500 && !haveLong; attempt++) {
        CheckersEngine walk = customCheckers({{3, 2, CheckersEngine::WhiteKing},
                                              {4, 5, CheckersEngine::BlackKing}},
                                             Side::White);
        std::map<std::string, int> seen;
        seen[walk.boardDiagram()] = 1;
        std::mt19937 rnd(attempt);
        bool stuck = false;
        for (int ply = 0; ply < CheckersEngine::DrawPlyLimit; ply++) {
            std::vector<CheckersMove> safe;
            for (auto& m : walk.legalMoves()) {
                if (m.isJump()) continue;
                CheckersEngine probe = walk.copy();
                probe.applyMove(m);
                auto it = seen.find(probe.boardDiagram());
                if (it != seen.end() && it->second >= 2) continue;
                if (kingsDiagonallyAdjacent(probe)) continue;
                safe.push_back(m);
            }
            if (safe.empty()) {
                stuck = true;
                break;
            }
            walk.applyMove(safe[rnd() % safe.size()]);
            seen[walk.boardDiagram()]++;
            if (walk.isGameOver() && ply < CheckersEngine::DrawPlyLimit - 1) {
                stuck = true;
                break;
            }
        }
        if (!stuck) {
            longGame = walk;
            haveLong = true;
        }
    }
    check(haveLong, "checkers: a capture-free 80-ply walk exists");
    if (haveLong) {
        check(longGame.outcome() == CheckersEngine::Outcome::Draw,
              "checkers: 80 ply without capture/crowning is a draw");
        check(longGame.outcomeReason().find("40-move") != std::string::npos,
              "checkers: 40-move reason reported");
    }
}

long perft(ChessEngine e, int depth) {
    if (depth == 0) return 1;
    long n = 0;
    for (auto& m : e.legalMoves()) {
        ChessEngine c = e.copy();
        c.applyUnchecked(m);
        n += perft(c, depth - 1);
    }
    return n;
}

void chessPerft() {
    ChessEngine e;
    eqStr(ChessEngine::StartFen, e.toFen(), "chess: start FEN");
    eqInt(20, perft(e, 1), "chess: perft(1) = 20");
    eqInt(400, perft(e, 2), "chess: perft(2) = 400");
    eqInt(8902, perft(e, 3), "chess: perft(3) = 8902");
    ChessEngine kiwi;
    kiwi.loadFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
    eqInt(97862, perft(kiwi, 3), "chess: Kiwipete perft(3) = 97862");
    eqInt(20, (int)e.legalMoves().size(), "chess: 20 opening moves");
}

void chessMatesAndCastling() {
    ChessEngine fools;
    for (auto s : {"f3", "e5", "g4", "Qh4#"}) fools.applyNotation(s);
    check(fools.outcome() == ChessEngine::Outcome::BlackWins, "chess: fool's mate for black");
    eqStr("Checkmate", fools.outcomeReason(), "chess: fool's mate reason");
    ChessEngine scholars;
    for (auto s : {"e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"}) scholars.applyNotation(s);
    check(scholars.outcome() == ChessEngine::Outcome::WhiteWins,
          "chess: scholar's mate for white");
    ChessEngine stale;
    stale.loadFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
    check(stale.outcome() == ChessEngine::Outcome::Draw, "chess: stalemate is a draw");
    eqStr("Stalemate", stale.outcomeReason(), "chess: stalemate reason");
    ChessEngine castle;
    castle.loadFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
    castle.applyNotation("O-O");
    eqInt(ChessPiece::King, ChessPiece::typeOf(castle.pieceAt(7, 6)),
          "chess: kingside castling moves the king to g1");
    eqInt(ChessPiece::Rook, ChessPiece::typeOf(castle.pieceAt(7, 5)),
          "chess: kingside castling moves the rook to f1");
    castle.applyNotation("O-O-O");
    eqInt(ChessPiece::King, ChessPiece::typeOf(castle.pieceAt(0, 2)),
          "chess: queenside castling moves the king to c8");
    eqInt(ChessPiece::Rook, ChessPiece::typeOf(castle.pieceAt(0, 3)),
          "chess: queenside castling moves the rook to d8");
    ChessEngine throughCheck;
    throughCheck.loadFen("5rk1/8/8/8/8/8/8/4K2R w K - 0 1");
    bool canCastle = false;
    for (auto& m : throughCheck.legalMoves())
        if (m.isCastling) canCastle = true;
    check(!canCastle, "chess: castling through check is illegal");
    ChessEngine inCheck;
    inCheck.loadFen("4r3/8/8/8/8/8/8/4K2R w K - 0 1");
    check(inCheck.inCheck(), "chess: test position is check");
    bool canCastle2 = false;
    for (auto& m : inCheck.legalMoves())
        if (m.isCastling) canCastle2 = true;
    check(!canCastle2, "chess: castling out of check is illegal");
    ChessEngine rights;
    rights.loadFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
    rights.applyNotation("Ra1a2");
    rights.applyNotation("Ra8a7");
    rights.applyNotation("Ra2a1");
    rights.applyNotation("Ra7a8");
    bool whiteQueenside = false, whiteKingside = false;
    for (auto& m : rights.legalMoves())
        if (m.isCastling) (m.toCol == 2 ? whiteQueenside : whiteKingside) = true;
    check(!whiteQueenside, "chess: queenside rook move forfeits queenside castling");
    check(whiteKingside, "chess: the untouched kingside castling right survives");
}

void chessEnPassantAndPromotion() {
    ChessEngine ep;
    for (auto s : {"e2e4", "a7a6", "e4e5", "d7d5", "e5d6"}) ep.applyNotation(s);
    eqInt(ChessPiece::Pawn, ChessPiece::typeOf(ep.pieceAt(2, 3)),
          "chess: en passant pawn lands on d6");
    eqInt(ChessPiece::Empty, ep.pieceAt(3, 3), "chess: en passant removes the bypassed pawn");
    ChessEngine staleEp;
    for (auto s : {"e2e4", "a7a6", "e4e5", "d7d5"}) staleEp.applyNotation(s);
    staleEp.applyNotation("a2a3");
    staleEp.applyNotation("a6a5");
    rejects([&] { staleEp.applyNotation("e5d6"); },
            "chess: en passant is only available on the next move");
    ChessEngine promo;
    promo.loadFen("8/P7/8/8/8/8/k6K/8 w - - 0 1");
    promo.applyNotation("a8=Q");
    eqInt(ChessPiece::of(ChessPiece::Queen, Side::White), promo.pieceAt(0, 0),
          "chess: SAN promotion creates a queen");
    ChessEngine promo2;
    promo2.loadFen("8/P7/8/8/8/8/k6K/8 w - - 0 1");
    promo2.applyNotation("a7a8n");
    eqInt(ChessPiece::of(ChessPiece::Knight, Side::White), promo2.pieceAt(0, 0),
          "chess: coordinate underpromotion creates a knight");
}

void chessPinsAndDraws() {
    ChessEngine pin;
    pin.loadFen("4r3/8/8/8/8/8/4R3/4K2k w - - 0 1");
    std::set<std::string> rookMoves;
    for (auto& m : pin.legalMovesFor(6, 4)) rookMoves.insert(ChessMove::squareName(m.toRow, m.toCol));
    check(rookMoves.count("e3"), "chess: pinned rook slides along the file");
    check(!rookMoves.count("d2"), "chess: pinned rook cannot leave the file");
    check(!rookMoves.count("f2"), "chess: pinned rook cannot leave the file (2)");
    ChessEngine fifty;
    fifty.loadFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 99 1");
    fifty.applyNotation("a1a2");
    check(fifty.outcome() == ChessEngine::Outcome::Draw, "chess: fifty-move rule at 100 halfmoves");
    eqStr("Fifty-move rule", fifty.outcomeReason(), "chess: fifty-move reason");
    ChessEngine kingsOnly;
    kingsOnly.loadFen("8/8/8/8/8/8/8/4K2k w - - 0 1");
    check(kingsOnly.outcome() == ChessEngine::Outcome::Draw, "chess: king vs king is a draw");
    ChessEngine bishopOnly;
    bishopOnly.loadFen("8/8/8/8/8/2B5/8/4K2k w - - 0 1");
    check(bishopOnly.outcome() == ChessEngine::Outcome::Draw,
          "chess: king+bishop vs king is a draw");
    ChessEngine knightBishop;
    knightBishop.loadFen("8/8/8/8/8/2N5/8/4K1bk w - - 0 1");
    check(knightBishop.outcome() == ChessEngine::Outcome::InProgress,
          "chess: knight vs bishop is not insufficient material");
    ChessEngine rep;
    for (auto s : {"Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8"})
        rep.applyNotation(s);
    check(rep.outcome() == ChessEngine::Outcome::Draw, "chess: threefold repetition is a draw");
    ChessEngine fen;
    fen.applyNotation("e4");
    eqStr("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", fen.toFen(),
          "chess: FEN after 1.e4");
    ChessEngine illegal;
    std::string before = illegal.toFen();
    rejects([&] { illegal.applyNotation("e2e5"); }, "chess: e2-e5 rejected as illegal");
    eqStr(before, illegal.toFen(), "chess: rejected move leaves FEN unchanged");
    rejects([&] { illegal.applyNotation("Qh5"); }, "chess: Qh5 rejected when queen is blocked");
}

void chessParsing() {
    ChessEngine amb;
    amb.loadFen("7k/8/8/8/8/8/8/1N1N1K2 w - - 0 1");
    rejects([&] { amb.applyNotation("Nc3"); }, "chess: ambiguous SAN is rejected");
    amb.applyNotation("Nbc3");
    eqInt(ChessPiece::of(ChessPiece::Knight, Side::White), amb.pieceAt(5, 2),
          "chess: file disambiguation selects the b1 knight");
    ChessEngine amb2;
    amb2.loadFen("7k/8/8/8/8/8/8/1N1N1K2 w - - 0 1");
    amb2.applyNotation("Ndc3");
    eqInt(ChessPiece::of(ChessPiece::Knight, Side::White), amb2.pieceAt(5, 2),
          "chess: file disambiguation selects the d1 knight");
    ChessEngine mix;
    mix.applyNotation("e2-e4");
    mix.applyNotation("e7-e5");
    mix.applyNotation("d2d4");
    mix.applyNotation("exd4");
    eqInt(ChessPiece::of(ChessPiece::Pawn, Side::Black), mix.pieceAt(4, 3),
          "chess: SAN pawn capture works");
    ChessEngine checkSuffix;
    checkSuffix.applyNotation("e4");
    checkSuffix.applyNotation("e5");
    checkSuffix.applyNotation("Bc4");
    checkSuffix.applyNotation("Nc6");
    checkSuffix.applyNotation("Qh5");
    checkSuffix.applyNotation("Nf6");
    checkSuffix.applyNotation("Qxf7+");
    check(checkSuffix.inCheck(), "chess: '+' suffix is ignored, check is real");
}

void controllerBehaviour() {
    GameController c;
    check(c.mode() == GameController::Mode::Checkers, "controller: starts in checkers");
    check(!c.movableSquares().empty(), "controller: movable squares reported");
    auto first = c.movableSquares()[0];
    auto sel = c.click(first.first, first.second);
    check(sel.outcome == GameController::ClickOutcome::Selected, "controller: click selects");
    check(!c.selectedDestinations().empty(), "controller: destinations exposed after selection");
    auto dest = c.selectedDestinations()[0];
    auto moved = c.click(std::get<0>(dest), std::get<1>(dest));
    check(moved.outcome == GameController::ClickOutcome::Moved, "controller: click applies move");
    eqInt(1, (int)c.moveHistory().size(), "controller: move recorded");
    c.setClockMinutes(1);
    check(!c.isGameOver(), "controller: game live before flag fall");
    c.tick(60 * 1000L);
    check(c.isGameOver(), "controller: flag fall ends the game");
    check(c.outcomeText().find("on time") != std::string::npos,
          "controller: timeout outcome text");
    GameController p;
    p.newGame(GameController::Mode::Chess);
    p.chess().loadFen("8/P7/8/8/8/8/k6K/8 w - - 0 1");
    auto s2 = p.click(1, 0);
    check(s2.outcome == GameController::ClickOutcome::Selected,
          "controller: promotion pawn selects");
    auto promo = p.click(0, 0);
    check(promo.outcome == GameController::ClickOutcome::NeedPromotion,
          "controller: promotion click asks for a piece");
    auto done = p.applyPromotion(ChessPiece::Queen);
    check(done.outcome == GameController::ClickOutcome::Moved, "controller: promotion applies");
    eqInt(ChessPiece::of(ChessPiece::Queen, Side::White), p.chess().pieceAt(0, 0),
          "controller: promotion creates the chosen piece");
    GameController net;
    net.checkers().clearBoard();
    net.checkers().setPiece(5, 2, CheckersEngine::WhiteMan);
    net.checkers().setPiece(4, 3, CheckersEngine::BlackMan);
    net.checkers().setPiece(2, 5, CheckersEngine::BlackMan);
    net.checkers().setPiece(0, 1, CheckersEngine::BlackMan);
    net.checkers().setSideToMove(Side::White);
    check(net.applyRemoteMove("c3xe5"), "controller: remote chain step 1 applies");
    check(net.checkers().isChainActive(), "controller: chain open after remote step 1");
    check(net.applyRemoteMove("e5xg7"), "controller: remote chain step 2 applies");
    check(!net.checkers().isChainActive(), "controller: chain closed after remote step 2");
    GameController loc;
    loc.setLocalSide(Side::White, true);
    auto blackSq = loc.movableSquares()[0];
    auto ignored = loc.click(blackSq.first, blackSq.second);
    check(ignored.outcome == GameController::ClickOutcome::Ignored,
          "controller: clicks are ignored on the opponent's turn");
    loc.setLocalSide(Side::Black, true);
    auto allowed = loc.click(blackSq.first, blackSq.second);
    check(allowed.outcome == GameController::ClickOutcome::Selected,
          "controller: clicks work on the local side's turn");
}

}  // namespace

int runEngineTests() {
    testutil::reset();
    checkersInitial();
    checkersMenAndCaptures();
    checkersChainsAndCrowning();
    checkersEndgames();
    chessPerft();
    chessMatesAndCastling();
    chessEnPassantAndPromotion();
    chessPinsAndDraws();
    chessParsing();
    controllerBehaviour();
    return testutil::report("EngineTests");
}
