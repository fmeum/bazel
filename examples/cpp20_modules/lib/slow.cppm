// A module of the same library that the app doesn't import. The demo's
// compiler wrapper makes compiling it take a while.
export module greeting.slow;

export int answer() { return 42; }
