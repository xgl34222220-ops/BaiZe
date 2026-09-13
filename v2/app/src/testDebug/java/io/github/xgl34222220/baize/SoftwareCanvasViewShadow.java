package io.github.xgl34222220.baize;

import android.view.View;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowView;

/** Test-only capability model for VisualCapture's Bitmap Canvas (never a GPU capture).
 * The production renderer and shader policy remain unchanged; GPU validation needs
 * a hardware surface on an emulator/device and is not claimed by these tests.
 */
@Implements(View.class)
public class SoftwareCanvasViewShadow extends ShadowView {
    @Implementation
    protected boolean isHardwareAccelerated() {
        return false;
    }
}
