using System;
using System.Reflection;
using System.Threading.Tasks;
using GorillaNetworking;
using HarmonyLib;

namespace GorillaPassthrough
{
    /// <summary>
    /// Keeps Gorilla Tag out of every online room while the passthrough runs. Another Axiom bans modded players in
    /// public lobbies, and the Minecraft world is single-player anyway.
    /// </summary>
    internal static class OfflineGuard
    {
        private static readonly string[] VoidJoins =
        {
            "AttemptToJoinPublicRoom", "AttemptToJoinPublicRoomAsync", "AttemptToJoinRankedPublicRoom",
            "AttemptToJoinRankedPublicRoomAsync", "AttemptToJoinSpecificRoom", "AttemptToJoinSpecificRoomWithCallback",
            "AttemptToAutoJoinSpecificRoom", "AttemptToFollowIntoPub",
        };

        public static void Apply(Harmony harmony)
        {
            var blockVoid = new HarmonyMethod(typeof(OfflineGuard).GetMethod(nameof(BlockVoid), BindingFlags.NonPublic | BindingFlags.Static));
            var blockTask = new HarmonyMethod(typeof(OfflineGuard).GetMethod(nameof(BlockTask), BindingFlags.NonPublic | BindingFlags.Static));
            int patched = 0;
            foreach (string name in VoidJoins)
            {
                patched += Patch(harmony, typeof(PhotonNetworkController), name, blockVoid);
            }
            patched += Patch(harmony, typeof(PhotonNetworkController), "AttemptToJoinSpecificRoomAsync", blockTask);
            Plugin.Log.LogInfo("Gorilla Passthrough: blocked " + patched + " ways of joining an online room");
        }

        public static void LeaveRoom()
        {
            if (NetworkSystem.Instance != null && NetworkSystem.Instance.InRoom)
            {
                Plugin.Log.LogInfo("Gorilla Passthrough: leaving the online room, the Minecraft world is single-player");
                NetworkSystem.Instance.ReturnToSinglePlayer();
            }
        }

        private static int Patch(Harmony harmony, Type type, string name, HarmonyMethod prefix)
        {
            foreach (MethodInfo method in type.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.DeclaredOnly))
            {
                if (method.Name != name || method.IsAbstract || method.GetMethodBody() == null)
                {
                    continue;
                }
                try
                {
                    harmony.Patch(method, prefix: prefix);
                    return 1;
                }
                catch (Exception e)
                {
                    Plugin.Log.LogWarning("Gorilla Passthrough: couldn't block " + type.Name + "." + name + ": " + e.Message);
                }
            }
            return 0;
        }

        private static bool BlockVoid(MethodBase __originalMethod)
        {
            Plugin.Log.LogInfo("Gorilla Passthrough: blocked " + __originalMethod.Name + " - Gorilla Tag stays offline during the passthrough");
            return false;
        }

        private static bool BlockTask(MethodBase __originalMethod, ref Task __result)
        {
            __result = Task.CompletedTask;
            return BlockVoid(__originalMethod);
        }
    }
}
