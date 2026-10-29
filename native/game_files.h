#pragma once
// The game-file detection shared between the launcher's probe (native/launcher_bridge.cpp) and the game's own
// FindGame (native/game_files.cpp), so the launcher's verdict is the one the game would reach.
#include <filesystem>

namespace app {

enum class Probe {
    NotFound,      // nothing playable in any candidate folder
    Extracted,     // a folder with this build's default.xbe and its data beside it
    Iso,           // a disc image holding this build's default.xbe
    WrongRelease,  // a copy was found, but its xbe is a different release than this build
};

struct ProbeResult {
    Probe status;
    std::filesystem::path dir;  // the folder (or image) the status is about; empty for NotFound
};

// Read-only: scans the candidate folders and checks the xbe's SHA-1 against this build, without mounting a disc,
// touching any global state, or calling SDL. own_assets is the app's own assets folder (the game passes
// ExeDir()/assets; the launcher passes the files dir Android gave it, so it can ask before SDL is up). FindGame
// reaches the same verdict before it goes on to mount and boot.
ProbeResult ProbeGame(const std::filesystem::path& own_assets);

}  // namespace app
