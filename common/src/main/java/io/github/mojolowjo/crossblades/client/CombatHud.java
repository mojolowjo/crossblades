package io.github.mojolowjo.crossblades.client;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.AttackDir;
import io.github.mojolowjo.crossblades.core.FighterState;
import io.github.mojolowjo.crossblades.core.Guard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws your loaded attack as a small arrow next to the crosshair, your guard as a bar while you
 * block, and short feedback text under it. It never shows anything about your opponent: reading
 * them is up to you.
 */
public final class CombatHud {
    private static final int ATTACK_COLOR = 0xE6FFFFFF;
    private static final int WINDUP_COLOR = 0xF0FFB347;
    private static final int BUSY_COLOR = 0x80A0A0A0;
    private static final int GUARD_COLOR = 0xE655D9FF;

    private CombatHud() {
    }

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        int cx = graphics.guiWidth() / 2;
        int cy = graphics.guiHeight() / 2;

        if (CombatClient.isActive() && Crossblades.settings().client.showDirectionArrow) {
            FighterState.Phase phase = CombatClient.localPhase();
            int color = switch (phase) {
                case WINDUP -> WINDUP_COLOR;
                case RECOVERY, STAGGER -> BUSY_COLOR;
                case IDLE -> ATTACK_COLOR;
            };
            drawAttackArrow(graphics, cx, cy, CombatClient.loadedAttack(), color);
            if (CombatClient.isGuarding()) {
                drawGuard(graphics, cx, cy, CombatClient.guardDir());
            }
        }

        String text = CombatClient.feedbackText();
        if (text != null) {
            float alpha = CombatClient.feedbackAlpha(deltaTracker.getGameTimeDeltaPartialTick(false));
            int a = Math.max(8, Math.round(alpha * 255));
            int color = (a << 24) | (CombatClient.feedbackColor() & 0xFFFFFF);
            graphics.centeredText(mc.font, text, cx, cy + 16, color);
        }
    }

    /** A small solid triangle pointing in the attack's direction, 9 pixels from the center. */
    private static void drawAttackArrow(GuiGraphicsExtractor g, int cx, int cy, AttackDir dir, int color) {
        int gap = 9;
        int size = 4;
        switch (dir) {
            case OVERHEAD -> {
                for (int i = 0; i < size; i++) {
                    int y = cy - gap - size + i;
                    g.fill(cx - i, y, cx + i + 1, y + 1, color);
                }
            }
            case POKE -> {
                for (int i = 0; i < size; i++) {
                    int y = cy + gap + size - 1 - i;
                    g.fill(cx - i, y, cx + i + 1, y + 1, color);
                }
            }
            case LEFT -> {
                for (int i = 0; i < size; i++) {
                    int x = cx - gap - size + i;
                    g.fill(x, cy - i, x + 1, cy + i + 1, color);
                }
            }
            case RIGHT -> {
                for (int i = 0; i < size; i++) {
                    int x = cx + gap + size - 1 - i;
                    g.fill(x, cy - i, x + 1, cy + i + 1, color);
                }
            }
        }
    }

    /** A bar on the guarded side, a bit further out than the arrow. */
    private static void drawGuard(GuiGraphicsExtractor g, int cx, int cy, Guard guard) {
        int gap = 15;
        int half = 6;
        switch (guard) {
            case UP -> g.fill(cx - half, cy - gap - 2, cx + half + 1, cy - gap, GUARD_COLOR);
            case LEFT -> g.fill(cx - gap - 2, cy - half, cx - gap, cy + half + 1, GUARD_COLOR);
            case RIGHT -> g.fill(cx + gap + 1, cy - half, cx + gap + 3, cy + half + 1, GUARD_COLOR);
        }
    }
}
