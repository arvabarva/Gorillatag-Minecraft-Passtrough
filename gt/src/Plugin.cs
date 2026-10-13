using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// Gorilla Passthrough for Gorilla Tag: you are your Gorilla Tag gorilla in a real, running Minecraft world. Both
    /// games run at the same time; Minecraft draws its world from the gorilla's head and this plugin shows that picture
    /// in VR, while Gorilla Tag's own physics moves the gorilla over Minecraft's blocks.
    /// </summary>
    [BepInPlugin(Id, "Gorilla Passthrough", "0.1.0")]
    public class Plugin : BaseUnityPlugin
    {
        public const string Id = "com.gorillapass.gorillatag";

        internal static ManualLogSource Log;
        internal static ConfigEntry<bool> FlipVertical;
        internal static ConfigEntry<bool> DebugScreenshots;
        internal static ConfigEntry<float> HeldItemTilt;
        internal static ConfigEntry<float> HeldItemLower;
        internal static ConfigEntry<int> PictureHeight;

        private void Awake()
        {
            Log = Logger;
            var enabled = Config.Bind("General", "Enabled", true,
                "Play Gorilla Tag inside the running Minecraft world. Set to false to play normal (online) Gorilla Tag; the mod then does nothing.");
            FlipVertical = Config.Bind("Display", "FlipVertical", false,
                "Turn Minecraft's picture upside down (only needed if it shows upside down, e.g. with Minecraft's Vulkan renderer).");
            PictureHeight = Config.Bind("Display", "PictureHeight", 2048,
                "How many pixels tall Minecraft draws the world for the headset (the width follows the headset's view). Higher is sharper but costs more; 1600 to 2400 is a good range.");
            HeldItemTilt = Config.Bind("Hands", "HeldItemTiltDegrees", 35f,
                "How far a held sword or tool points up from where the controller points, in degrees.");
            HeldItemLower = Config.Bind("Hands", "HeldItemLowerMeters", 0.06f,
                "How far below the hand's tracking point a held item sits, so it's in the fist instead of above it.");
            DebugScreenshots = Config.Bind("Debug", "Screenshots", false,
                "Save screenshots to BepInEx/gorillapass-*.png 10 and 25 seconds after Minecraft's picture first shows.");
            if (!enabled.Value)
            {
                Log.LogInfo("Gorilla Passthrough is turned off (Enabled = false)");
                return;
            }

            // Gorilla Tag must stay offline: mods are only allowed outside public rooms.
            try
            {
                OfflineGuard.Apply(new Harmony(Id));
            }
            catch (System.Exception e)
            {
                Log.LogError("Gorilla Passthrough: offline guard failed: " + e);
            }

            var runner = new GameObject("Gorilla Passthrough");
            DontDestroyOnLoad(runner);
            runner.AddComponent<PassthroughRunner>();
            Log.LogInfo("Gorilla Passthrough loaded");
        }
    }
}
