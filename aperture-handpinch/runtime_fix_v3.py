#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: runtime_fix_v3.py <aperture-root>")

root = Path(sys.argv[1]).resolve()

def patch(rel, old, new):
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor not found in {rel}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

hdr = "app/src/universal3dof/cpp/DeviceDelegate3DOF.h"
patch(
    hdr,
    "  bool IsInGazeMode() const override { return true; }\n  int32_t GazeModeIndex() const override { return 0; }",
    "  bool IsInGazeMode() const override;\n  int32_t GazeModeIndex() const override;"
)

cpp = "app/src/universal3dof/cpp/DeviceDelegate3DOF.cpp"

# Keep gaze as the fallback when there is no hand, but advertise real positional
# capability while the hand ray is active.
patch(
    cpp,
    "    m.controller->SetCapabilityFlags(kControllerIndex, device::Orientation | device::PositionEmulated);",
    "    m.controller->SetCapabilityFlags(kControllerIndex, device::Orientation | device::PositionEmulated);"
)

old_hand = '''void DeviceDelegate3DOF::HandPointer(const bool aActive, const float aX, const float aY) {
  m.handPointerActive = aActive;
  m.handPointerX = std::max(0.0f, std::min(1.0f, aX));
  m.handPointerY = std::max(0.0f, std::min(1.0f, aY));
  if (!aActive && m.controller) {
    m.controller->SetSelectFactor(kControllerIndex, 0.0f);
  }
}
'''
new_hand = '''void DeviceDelegate3DOF::HandPointer(const bool aActive, const float aX, const float aY) {
  const bool changed = (m.handPointerActive != aActive);
  m.handPointerActive = aActive;
  m.handPointerX = std::max(0.0f, std::min(1.0f, aX));
  m.handPointerY = std::max(0.0f, std::min(1.0f, aY));

  if (m.controller && changed) {
    if (aActive) {
      // IMPORTANT: Gaze rays are forced to the head centre by BrowserWorld.
      // A fingertip-controlled ray must be a genuine tracked pointer.
      m.controller->SetTargetRayMode(kControllerIndex, device::TargetRayMode::TrackedPointer);
      m.controller->SetCapabilityFlags(kControllerIndex, device::Orientation | device::Position);
    } else {
      m.controller->SetTargetRayMode(kControllerIndex, device::TargetRayMode::Gaze);
      m.controller->SetCapabilityFlags(kControllerIndex, device::Orientation | device::PositionEmulated);
      m.controller->SetGazeModeIndex(kControllerIndex);
      m.controller->SetSelectFactor(kControllerIndex, 0.0f);
    }
  }
}
'''
patch(cpp, old_hand, new_hand)

# BrowserWorld checks these methods every frame. Gaze mode must be disabled while
# the hand is active, otherwise it overrides the tracked pointer with head gaze.
insert_anchor = '''void DeviceDelegate3DOF::ReleaseControllerDelegate() {
  m.controller = nullptr;
}
'''
insert_new = '''void DeviceDelegate3DOF::ReleaseControllerDelegate() {
  m.controller = nullptr;
}

bool DeviceDelegate3DOF::IsInGazeMode() const {
  return !m.handPointerActive;
}

int32_t DeviceDelegate3DOF::GazeModeIndex() const {
  return m.handPointerActive ? -1 : kControllerIndex;
}
'''
patch(cpp, insert_anchor, insert_new)

print("Aperture tracked-pointer hand routing v3 applied")
