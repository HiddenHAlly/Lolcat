package net.hiddenhally.client.sort;

import net.hiddenhally.client.containers.HighlightRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Come raggiungere un contenitore. Se Baritone è installato (anche un fork per 26.3) lo usa tramite
 * reflection, senza dipendenza obbligatoria; altrimenti modalità "assistita": evidenzia il prossimo
 * contenitore e aspetta che sia tu ad avvicinarti (poi continua da solo).
 */
public interface Navigator {

    void goNear(Minecraft mc, BlockPos pos);

    void stop();

    String name();

    /** true se il bot si muove da solo. */
    boolean autonomous();

    static Navigator create() {
        try {
            return new BaritoneNavigator();
        } catch (Throwable t) {
            return new AssistNavigator();
        }
    }

    // ------------------------------------------------------------------ assistita

    final class AssistNavigator implements Navigator {
        @Override
        public void goNear(Minecraft mc, BlockPos pos) {
            HighlightRenderer.setTarget(pos);
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§e[Lolcat] Avvicinati al contenitore evidenziato in bianco ("
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")."));
            }
        }

        @Override
        public void stop() {
            HighlightRenderer.setTarget(null);
        }

        @Override
        public String name() {
            return "assistita (senza Baritone)";
        }

        @Override
        public boolean autonomous() {
            return false;
        }
    }

    // ------------------------------------------------------------------ Baritone via reflection

    final class BaritoneNavigator implements Navigator {
        private final Object pathing;
        private final Object customGoal;
        private final Method setGoalAndPath;
        private final Method cancelEverything;
        private final Constructor<?> goalNearCtor;
        private final Map<String, Object[]> savedSettings = new HashMap<>();
        private final Object settings;

        BaritoneNavigator() throws Exception {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Object baritone = providerClass.getMethod("getPrimaryBaritone").invoke(provider);
            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            this.customGoal = baritoneClass.getMethod("getCustomGoalProcess").invoke(baritone);
            this.pathing = baritoneClass.getMethod("getPathingBehavior").invoke(baritone);

            Class<?> goal = Class.forName("baritone.api.pathing.goals.Goal");
            this.setGoalAndPath = Class.forName("baritone.api.process.ICustomGoalProcess").getMethod("setGoalAndPath", goal);
            this.cancelEverything = Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything");
            this.goalNearCtor = Class.forName("baritone.api.pathing.goals.GoalNear").getConstructor(BlockPos.class, int.class);
            this.settings = api.getMethod("getSettings").invoke(null);

            // durante l'ordinamento Baritone non deve rompere né piazzare blocchi
            forceSetting("allowBreak", false);
            forceSetting("allowPlace", false);
        }

        private void forceSetting(String name, Object value) {
            try {
                Object setting = settings.getClass().getField(name).get(settings);
                java.lang.reflect.Field v = setting.getClass().getField("value");
                savedSettings.put(name, new Object[]{setting, v.get(setting)});
                v.set(setting, value);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void goNear(Minecraft mc, BlockPos pos) {
            try {
                Object goal = goalNearCtor.newInstance(pos, 3);
                setGoalAndPath.invoke(customGoal, goal);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        @Override
        public void stop() {
            try {
                cancelEverything.invoke(pathing);
            } catch (Throwable ignored) {
            }
            for (Object[] saved : savedSettings.values()) {
                try {
                    saved[0].getClass().getField("value").set(saved[0], saved[1]);
                } catch (Throwable ignored) {
                }
            }
            savedSettings.clear();
        }

        @Override
        public String name() {
            return "Baritone";
        }

        @Override
        public boolean autonomous() {
            return true;
        }
    }
}
