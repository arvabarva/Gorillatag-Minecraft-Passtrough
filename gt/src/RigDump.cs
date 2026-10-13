using System.IO;
using System.Text;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>Debug: writes the local gorilla rig's hierarchy (objects, components, bones) to BepInEx/gorillapass-rig.txt.</summary>
    internal static class RigDump
    {
        public static void Write(VRRig rig)
        {
            if (rig == null)
            {
                return;
            }
            var text = new StringBuilder();
            text.AppendLine("rig " + rig.name + " scale " + rig.transform.lossyScale + " at " + rig.transform.position);
            text.AppendLine("head rigTarget " + Describe(rig.head?.rigTarget) + " headConstraint " + Describe(rig.headConstraint));
            text.AppendLine("leftHand rigTarget " + Describe(rig.leftHand?.rigTarget) + " rightHand rigTarget " + Describe(rig.rightHand?.rigTarget));
            text.AppendLine("leftHandTransform " + Describe(rig.leftHandTransform) + " rightHandTransform " + Describe(rig.rightHandTransform));
            text.AppendLine("bodyTransform " + Describe(rig.bodyTransform));
            text.AppendLine("headMesh " + Describe(rig.headMesh != null ? rig.headMesh.transform : null) + " playerColor " + rig.playerColor);
            if (rig.mainSkin != null)
            {
                SkinnedMeshRenderer skin = rig.mainSkin;
                text.AppendLine("mainSkin " + Describe(skin.transform) + " rootBone " + Describe(skin.rootBone) + " mesh " + skin.sharedMesh?.name
                    + " materials " + string.Join(", ", System.Array.ConvertAll(skin.sharedMaterials, m => m == null ? "null" : m.name + " (" + m.shader.name + ")")));
                text.AppendLine("bones: " + string.Join(" | ", System.Array.ConvertAll(skin.bones, b => b == null ? "null" : Path(b))));
            }
            text.AppendLine();
            foreach (Component component in rig.GetComponentsInChildren<Component>(true))
            {
                string type = component != null ? component.GetType().Name : "";
                if (type == "GorillaBodyRenderer" || type == "MaterialCombinerPerRendererMono" || type == "ForceRecalculateBounds")
                {
                    Members(component, text);
                }
            }
            text.AppendLine();
            Walk(rig.transform, 0, text);
            string path = System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-rig.txt");
            File.WriteAllText(path, text.ToString());
            Plugin.Log.LogInfo("Gorilla Passthrough: rig written to " + path);
        }

        /// <summary>A Gorilla Tag script's fields (with what they hold) and methods.</summary>
        private static void Members(Component component, StringBuilder text)
        {
            const System.Reflection.BindingFlags all = System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.Static
                | System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.DeclaredOnly;
            System.Type type = component.GetType();
            text.AppendLine(type.FullName + " on " + Path(component.transform) + (component is Behaviour b ? (b.enabled ? "" : " [disabled]") : ""));
            foreach (System.Reflection.FieldInfo field in type.GetFields(all))
            {
                string value;
                try
                {
                    object v = field.GetValue(field.IsStatic ? null : component);
                    value = v is Component c ? (c == null ? "null" : c.GetType().Name + " " + Path(c.transform))
                        : v is GameObject g ? (g == null ? "null" : Path(g.transform))
                        : v is System.Collections.ICollection list ? v.GetType().Name + "[" + list.Count + "]"
                        : v?.ToString() ?? "null";
                }
                catch (System.Exception e)
                {
                    value = "? " + e.GetType().Name;
                }
                text.AppendLine("  field " + (field.IsStatic ? "static " : "") + field.FieldType.Name + " " + field.Name + " = " + value);
            }
            foreach (System.Reflection.MethodInfo method in type.GetMethods(all))
            {
                text.AppendLine("  method " + method.Name + "(" + string.Join(", ", System.Array.ConvertAll(method.GetParameters(), p => p.ParameterType.Name)) + ")");
            }
        }

        private static void Walk(Transform node, int depth, StringBuilder text)
        {
            if (depth > 14)
            {
                return;
            }
            var line = new StringBuilder();
            line.Append(new string(' ', depth * 2)).Append(node.name).Append(node.gameObject.activeSelf ? "" : " [off]")
                .Append(" layer ").Append(node.gameObject.layer)
                .Append(" lp ").Append(node.localPosition.ToString("F3")).Append(" lr ").Append(node.localEulerAngles.ToString("F0"))
                .Append(" ls ").Append(node.localScale.ToString("F2")).Append(" :");
            foreach (Component component in node.GetComponents<Component>())
            {
                if (component != null && !(component is Transform))
                {
                    line.Append(' ').Append(component.GetType().Name);
                    if (component is Renderer renderer && !renderer.enabled)
                    {
                        line.Append("[disabled]");
                    }
                }
            }
            text.AppendLine(line.ToString());
            foreach (Transform child in node)
            {
                Walk(child, depth + 1, text);
            }
        }

        private static string Describe(Transform t) => t == null ? "null" : Path(t) + " @" + t.position.ToString("F3");

        private static string Path(Transform t)
        {
            string path = t.name;
            for (Transform p = t.parent; p != null; p = p.parent)
            {
                path = p.name + "/" + path;
            }
            return path;
        }
    }
}
