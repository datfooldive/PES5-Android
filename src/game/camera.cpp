// The match camera's distance and height, percentages of the title's (game::Opt().camera_distance and camera_height,
// 100 the title's own).
//
// The camera director, sub_00394DB0, runs once a frame and picks a scene by the byte at 0x9AD3E0. The scenes of play
// (sub_003985B0), the restarts and the set pieces' way back to play hand the camera's eye and target to
// sub_00397000, which eases them toward a framing it works out from the player's camera type (0x9AD3F3): 9 int16 a
// type in a table at 0x7AB838. The eye's offset from the target is three of them: the third along the pitch (x, the
// cameras behind a goal), the fourth its height (y, up is negative), the fifth across the pitch (z, the cameras on the
// side). sub_00395390 and the director's scene 6 (sub_00395630) place the camera from the same table without easing.
// Every reader of the table is one of these; replays of recorded play aside, the menus, the cut scenes and the
// animated cameras (sub_00094910) set the eye, the target or the view matrix themselves and never read it.
//
// So both are those fields, scaled in the table rather than in the eye: the easing and the clamps work on them as on
// the title's, the moves between the scenes of play stay smooth, and a change during play eases in. The distance
// scales the whole offset, so the camera moves back along its line of sight and keeps the title's angle, where the
// across offset alone would flatten it toward a view from the side; the height then scales the height on its own.
#include <algorithm>
#include <cstdint>
#include <iterator>

#include "common/options.h"
#include "kernel/kernel.h"
#include "xdk/hle.h"

using namespace kernel;

namespace {

constexpr uint32_t kCameraTypes = 0x7AB838;  // 13 types of 9 int16
constexpr uint32_t kTypeSize = 18;
constexpr uint32_t kTypeCount = 13;
// the eye's offset, in the order the type has it
constexpr uint32_t kAlong = 4, kHeight = 6, kAcross = 8;
constexpr uint32_t kFields[] = {kAlong, kHeight, kAcross};
constexpr uint32_t kFieldCount = std::size(kFields);

int16_t g_title[kTypeCount][kFieldCount];    // the title's offsets, as the image has them
int16_t g_written[kTypeCount][kFieldCount];  // what was put in their place
uint32_t g_distance = 0, g_height = 0;       // the percentages they were made for, 0 before the first

uint32_t At(uint32_t type, uint32_t field) { return kCameraTypes + type * kTypeSize + kFields[field]; }

int16_t Scale(int16_t v, int32_t percent) {
    const int32_t scaled = (int32_t(v) * percent + (v < 0 ? -5000 : 5000)) / 10000;
    return int16_t(std::clamp<int32_t>(scaled, INT16_MIN, INT16_MAX));
}

// the table as the options want it, on the game's thread before a reader. A field that is not what was written was
// put back by the title (or never written): it is the title's again
void Apply() {
    const uint32_t distance = std::clamp(game::Opt().camera_distance, 70u, 150u);
    const uint32_t height = std::clamp(game::Opt().camera_height, 50u, 200u);
    bool changed = distance != g_distance || height != g_height;
    for (uint32_t t = 0; t < kTypeCount; t++)
        for (uint32_t f = 0; f < kFieldCount; f++) {
            const int16_t now = int16_t(R16(At(t, f)));
            if (g_distance && now == g_written[t][f]) continue;
            g_title[t][f] = now;
            changed = true;
        }
    if (!changed) return;
    for (uint32_t t = 0; t < kTypeCount; t++)
        for (uint32_t f = 0; f < kFieldCount; f++) {
            const uint32_t percent = distance * (kFields[f] == kHeight ? height : 100);
            g_written[t][f] = Scale(g_title[t][f], int32_t(percent));
            W16(At(t, f), uint16_t(g_written[t][f]));
        }
    g_distance = distance;
    g_height = height;
}

}  // namespace

// the camera director, each frame
X86_EXTERN_FUNC(__imp__sub_00394DB0);
X86_FUNC(sub_00394DB0) {
    Apply();
    __imp__sub_00394DB0(ctx, base);
}

// the gameplay camera, which the set pieces and the camera's set up also call outside the director
X86_EXTERN_FUNC(__imp__sub_00397000);
X86_FUNC(sub_00397000) {
    Apply();
    __imp__sub_00397000(ctx, base);
}
