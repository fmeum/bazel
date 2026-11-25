module;
#include <string>
export module greeting.strings;

export std::string exclaim(const std::string& s) { return s + "!"; }
