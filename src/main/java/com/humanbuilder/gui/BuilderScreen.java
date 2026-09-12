package com.humanbuilder.gui;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.state.BuilderStateMachine;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * In-game menu: pick a Litematica placement, see whether you have the materials,
 * and start building only when everything is available.
 */
public class BuilderScreen extends Screen {

    private static final int OK = 0xFF55FF55;
    private static final int BAD = 0xFFFF5555;
    private static final int MUTED = 0xFFB0B0B0;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF79D97B;

    private List<SchematicPlacement> placements = List.of();
    private SchematicPlacement selected;
    private MaterialChecker.Result result;

    private ButtonWidget startButton;
    private int listX = 12;
    private int infoX = 190;

    public BuilderScreen() {
        super(Text.literal("HumanBuilder"));
    }

    @Override
    protected void init() {
        this.placements = MaterialChecker.placements();
        if (selected == null) {
            selected = MaterialChecker.selected();
            if (selected == null && !placements.isEmpty()) selected = placements.get(0);
        }

        int y = 34;
        int shown = 0;
        for (SchematicPlacement p : placements) {
            if (shown >= 8) break;
            final SchematicPlacement placement = p;
            String label = trim(p.getName(), 22);
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> selectPlacement(placement))
                    .dimensions(listX, y, 160, 20).build());
            y += 22;
            shown++;
        }

        int by = this.height - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Re-check"), b -> recompute())
                .dimensions(listX, by, 78, 20).build());

        startButton = ButtonWidget.builder(Text.literal("Start building"), b -> startBuilding())
                .dimensions(listX + 84, by, 130, 20).build();
        addDrawableChild(startButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> stopBuilding())
                .dimensions(listX + 220, by, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> this.close())
                .dimensions(this.width - 74, by, 62, 20).build());

        recompute();
    }

    private void selectPlacement(SchematicPlacement p) {
        this.selected = p;
        var mgr = DataManager.getSchematicPlacementManager();
        if (mgr != null) mgr.setSelectedSchematicPlacement(p);
        recompute();
    }

    private void recompute() {
        if (selected != null && this.client != null) {
            result = MaterialChecker.compute(this.client, selected);
        } else {
            result = null;
        }
        if (startButton != null) {
            startButton.active = result != null && result.allSatisfied();
        }
    }

    private void startBuilding() {
        if (result == null || !result.allSatisfied() || selected == null) return;
        BuilderConfig.INSTANCE.buildBounds = result.bounds();
        BuilderConfig.INSTANCE.selectedSchematic = selected.getName();
        BuilderConfig.INSTANCE.enabled = true;
        BuilderStateMachine.INSTANCE.reset();
        this.close();
    }

    private void stopBuilding() {
        BuilderConfig.INSTANCE.enabled = false;
        BuilderStateMachine.INSTANCE.reset();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        ctx.drawTextWithShadow(this.textRenderer, Text.literal("§bHumanBuilder §7— build assistant"),
                listX, 12, WHITE);

        if (placements.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("§cNo Litematica placements loaded."), infoX, 34, BAD);
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("§7Load & place a schematic in Litematica first."), infoX, 48, MUTED);
            return;
        }

        String name = selected != null ? selected.getName() : "(none)";
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("§fSelected: §a" + trim(name, 30)), infoX, 34, WHITE);

        if (result == null) return;

        int y = 52;
        if (result.creative()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("§aCreative mode — materials always available"), infoX, y, OK);
            y += 14;
        }

        String status = result.allSatisfied()
                ? "§aREADY — all materials present"
                : "§cMISSING ITEMS — get these first";
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(status), infoX, y, result.allSatisfied() ? OK : BAD);
        y += 16;

        if (result.entries().isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("§7Nothing left to place in this schematic."), infoX, y, MUTED);
        } else {
            int max = 16;
            int shown = 0;
            for (MaterialChecker.Entry e : result.entries()) {
                if (shown >= max) {
                    ctx.drawTextWithShadow(this.textRenderer,
                            Text.literal("§7…and " + (result.entries().size() - max) + " more"),
                            infoX, y, MUTED);
                    break;
                }
                String mark = e.ok() ? "§a✔" : "§c✖";
                String line = mark + " §f" + trim(e.name(), 22) + " §7" + e.have() + " / " + e.need();
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(line), infoX, y, e.ok() ? OK : BAD);
                y += 11;
                shown++;
            }
        }

        if (result.truncated()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("§7(large schematic — counts are approximate)"), infoX, this.height - 46, MUTED);
        }
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("§7Pick a placement on the left, then Start."), listX, this.height - 46, MUTED);
    }

    private static String trim(String s, int max) {
        if (s == null) return "(unnamed)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public boolean shouldPause() {
        return false; // keep the world ticking behind the menu
    }
}
