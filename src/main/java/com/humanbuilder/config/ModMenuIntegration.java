package com.humanbuilder.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Optional Mod Menu + Cloth Config screen. Only active when both mods are
 * installed; the core builder runs fine without either. Entries read/write the
 * plain fields on {@link BuilderConfig}.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModMenuIntegration::build;
    }

    private static Screen build(Screen parent) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("HumanBuilder"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        general.addEntry(eb.startBooleanToggle(Text.literal("Enabled"), cfg.enabled)
                .setSaveConsumer(v -> cfg.enabled = v).build());
        general.addEntry(eb.startDoubleField(Text.literal("Fallback scan radius (no bounds)"), cfg.scanRadius)
                .setMin(8.0).setMax(512.0).setSaveConsumer(v -> cfg.scanRadius = v).build());
        general.addEntry(eb.startBooleanToggle(Text.literal("Block user input while active"), cfg.blockUserInput)
                .setSaveConsumer(v -> cfg.blockUserInput = v).build());
        general.addEntry(eb.startBooleanToggle(Text.literal("Show HUD overlay"), cfg.showHud)
                .setSaveConsumer(v -> cfg.showHud = v).build());
        general.addEntry(eb.startBooleanToggle(Text.literal("Action-bar status (if HUD off)"), cfg.reportEstimate)
                .setSaveConsumer(v -> cfg.reportEstimate = v).build());

        ConfigCategory order = builder.getOrCreateCategory(Text.literal("Build order"));
        order.addEntry(eb.startBooleanToggle(Text.literal("Serpentine (lawnmower) sweep"), cfg.serpentineSweep)
                .setTooltip(Text.literal("Cover each layer row-by-row instead of hopping to the nearest block — less walking and backtracking."))
                .setSaveConsumer(v -> cfg.serpentineSweep = v).build());
        order.addEntry(eb.startBooleanToggle(Text.literal("Structural blocks first"), cfg.structuralFirst)
                .setTooltip(Text.literal("Place the solid mass before slabs/stairs/torches/rails so supports exist first."))
                .setSaveConsumer(v -> cfg.structuralFirst = v).build());
        order.addEntry(eb.startBooleanToggle(Text.literal("Coverage standing (fewer walks)"), cfg.coverageStanding)
                .setTooltip(Text.literal("Walk to the spot that reaches the most blocks and place the whole cluster there, instead of hopping block-to-block. Biggest speed win."))
                .setSaveConsumer(v -> cfg.coverageStanding = v).build());

        ConfigCategory aim = builder.getOrCreateCategory(Text.literal("Aim realism"));
        aim.addEntry(eb.startBooleanToggle(Text.literal("Micro-tremor"), cfg.enableTremor)
                .setSaveConsumer(v -> cfg.enableTremor = v).build());
        aim.addEntry(eb.startBooleanToggle(Text.literal("Overshoot / correction"), cfg.enableOvershoot)
                .setSaveConsumer(v -> cfg.enableOvershoot = v).build());
        aim.addEntry(eb.startBooleanToggle(Text.literal("Mouse quantization"), cfg.enableQuantization)
                .setSaveConsumer(v -> cfg.enableQuantization = v).build());
        aim.addEntry(eb.startDoubleField(Text.literal("Overshoot chance"), cfg.overshootChance)
                .setMin(0.0).setMax(1.0).setSaveConsumer(v -> cfg.overshootChance = v).build());

        ConfigCategory timing = builder.getOrCreateCategory(Text.literal("Timing"));
        timing.addEntry(eb.startBooleanToggle(Text.literal("Pipeline aim with cooldown"), cfg.pipelineAim)
                .setTooltip(Text.literal("Aim at the next block while the last placement's cooldown elapses (faster, and how a person actually builds), instead of waiting then aiming."))
                .setSaveConsumer(v -> cfg.pipelineAim = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Click delay mean (ms)"), cfg.clickDelayMeanMs)
                .setMin(0.0).setSaveConsumer(v -> cfg.clickDelayMeanMs = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Click delay std (ms)"), cfg.clickDelayStdMs)
                .setMin(0.0).setSaveConsumer(v -> cfg.clickDelayStdMs = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Fatigue per minute"), cfg.fatiguePerMinute)
                .setMin(0.0).setSaveConsumer(v -> cfg.fatiguePerMinute = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Fatigue cap"), cfg.fatigueCap)
                .setMin(1.0).setSaveConsumer(v -> cfg.fatigueCap = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Misclick chance"), cfg.misclickChance)
                .setMin(0.0).setMax(1.0).setSaveConsumer(v -> cfg.misclickChance = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Hesitation chance"), cfg.hesitationChance)
                .setMin(0.0).setMax(1.0).setSaveConsumer(v -> cfg.hesitationChance = v).build());
        timing.addEntry(eb.startDoubleField(Text.literal("Pause below TPS"), cfg.pauseBelowTps)
                .setMin(0.0).setMax(20.0).setSaveConsumer(v -> cfg.pauseBelowTps = v).build());

        ConfigCategory nav = builder.getOrCreateCategory(Text.literal("Navigation"));
        nav.addEntry(eb.startBooleanToggle(Text.literal("Enable navigation"), cfg.enableNavigation)
                .setSaveConsumer(v -> cfg.enableNavigation = v).build());
        nav.addEntry(eb.startBooleanToggle(Text.literal("Show red path line"), cfg.showPath)
                .setSaveConsumer(v -> cfg.showPath = v).build());
        nav.addEntry(eb.startDoubleField(Text.literal("Navigate when farther than (blocks)"), cfg.navReach)
                .setMin(1.5).setMax(6.0).setSaveConsumer(v -> cfg.navReach = v).build());
        nav.addEntry(eb.startIntField(Text.literal("Max fall distance"), cfg.maxFallDistance)
                .setMin(0).setMax(10).setSaveConsumer(v -> cfg.maxFallDistance = v).build());
        nav.addEntry(eb.startIntField(Text.literal("Max A* iterations"), cfg.maxPathIterations)
                .setMin(500).setMax(20000).setSaveConsumer(v -> cfg.maxPathIterations = v).build());
        nav.addEntry(eb.startDoubleField(Text.literal("Turn speed (deg/tick)"), cfg.turnSpeedDegPerTick)
                .setMin(2.0).setMax(90.0).setSaveConsumer(v -> cfg.turnSpeedDegPerTick = v).build());
        nav.addEntry(eb.startBooleanToggle(Text.literal("Build staircase to reach high layers"), cfg.enableScaffolding)
                .setSaveConsumer(v -> cfg.enableScaffolding = v).build());
        nav.addEntry(eb.startIntField(Text.literal("Max scaffold blocks"), cfg.maxScaffoldBlocks)
                .setMin(0).setMax(512).setSaveConsumer(v -> cfg.maxScaffoldBlocks = v).build());

        builder.setSavingRunnable(BuilderConfig::save);
        return builder.build();
    }
}
