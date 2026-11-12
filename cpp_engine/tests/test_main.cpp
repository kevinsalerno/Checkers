// Test runner entry point. Aggregates every committed headless suite.
#include <iostream>

int runEngineTests();
int runSimulationTests();
int runTracerTests();
int runNetTests();

int main() {
    int failures = 0;
    failures += runEngineTests();
    failures += runSimulationTests();
    failures += runTracerTests();
    failures += runNetTests();
    if (failures == 0) {
        std::cout << "ALL TESTS PASSED\n";
        return 0;
    }
    std::cout << failures << " CHECK(S) FAILED\n";
    return 1;
}
