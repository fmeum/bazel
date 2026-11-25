module;
#include <string>
export module greeting;
import greeting.strings;

export std::string greet(const std::string& name) { return exclaim("Hello, " + name); }
