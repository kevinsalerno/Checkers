// Minimal assertion helpers for the committed headless tests.
// No test framework is used, keeping the zero-dependency constraint
// (mirrors the Java port's TestUtil).
#pragma once

#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>

namespace testutil {

inline int& checks() {
    static int c = 0;
    return c;
}
inline int& failures() {
    static int f = 0;
    return f;
}

inline void reset() {
    checks() = 0;
    failures() = 0;
}

inline void check(bool condition, const std::string& name) {
    checks()++;
    if (!condition) {
        failures()++;
        std::cout << "FAIL: " << name << "\n";
    }
}

inline void eqStr(const std::string& expected, const std::string& actual,
                  const std::string& name) {
    checks()++;
    if (expected != actual) {
        failures()++;
        std::cout << "FAIL: " << name << " expected <" << expected << "> but was <" << actual
                  << ">\n";
    }
}

inline void eqInt(long long expected, long long actual, const std::string& name) {
    checks()++;
    if (expected != actual) {
        failures()++;
        std::cout << "FAIL: " << name << " expected <" << expected << "> but was <" << actual
                  << ">\n";
    }
}

inline void eqDouble(double expected, double actual, const std::string& name) {
    checks()++;
    if (expected != actual) {
        failures()++;
        std::cout << "FAIL: " << name << " expected <" << expected << "> but was <" << actual
                  << ">\n";
    }
}

// Asserts that body throws std::invalid_argument.
inline void rejects(const std::function<void()>& body, const std::string& name) {
    checks()++;
    try {
        body();
        failures()++;
        std::cout << "FAIL: " << name << " (accepted, expected rejection)\n";
    } catch (const std::invalid_argument&) {
        // Expected.
    } catch (const std::exception& e) {
        failures()++;
        std::cout << "FAIL: " << name << " (wrong exception: " << e.what() << ")\n";
    }
}

inline int report(const std::string& suite) {
    std::cout << suite << ": " << (checks() - failures()) << "/" << checks()
              << " checks passed\n";
    return failures();
}

}  // namespace testutil
