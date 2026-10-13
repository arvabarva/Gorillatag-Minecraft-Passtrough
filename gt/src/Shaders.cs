using UnityEngine;
using UnityEngine.Rendering;

namespace GorillaPassthrough
{
    /// <summary>
    /// Unlit textured materials from shaders already in Gorilla Tag's build. "Universal Render Pipeline/Particles/Unlit"
    /// is the one GorillaCraft found to work for opaque, depth-writing geometry.
    /// </summary>
    internal static class Shaders
    {
        private static readonly string[] Candidates =
        {
            "Universal Render Pipeline/Particles/Unlit",
            "Universal Render Pipeline/Unlit",
            "Unlit/Texture",
            "Sprites/Default",
        };

        public static Material CreateUnlit(string name)
        {
            Shader shader = null;
            string found = null;
            foreach (string candidate in Candidates)
            {
                shader = Shader.Find(candidate);
                if (shader != null && shader.isSupported)
                {
                    found = candidate;
                    break;
                }
            }
            if (found == null)
            {
                Plugin.Log.LogError("Gorilla Passthrough: no unlit shader in Gorilla Tag's build");
                shader = Shader.Find("Hidden/InternalErrorShader");
            }
            var material = new Material(shader) { name = name, renderQueue = (int)RenderQueue.Geometry };
            if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", Color.white);
            if (material.HasProperty("_Color")) material.SetColor("_Color", Color.white);
            if (material.HasProperty("_Cull")) material.SetFloat("_Cull", (float)CullMode.Off);
            if (material.HasProperty("_ZWrite")) material.SetFloat("_ZWrite", 1f);
            Plugin.Log.LogInfo("Gorilla Passthrough: '" + name + "' uses shader '" + found + "'");
            return material;
        }

        /// <summary>
        /// Alpha blending without a custom shader: URP's particle shader takes its blend factors from the material.
        /// Normal: shows the texture where its alpha is 1. Inverted: shows it where alpha is 0, and what is behind where
        /// alpha is 1. Both still write depth, like solid geometry.
        /// </summary>
        public static void MakeBlended(Material material, bool inverted, int queue)
        {
            float src = (float)(inverted ? BlendMode.OneMinusSrcAlpha : BlendMode.SrcAlpha);
            float dst = (float)(inverted ? BlendMode.SrcAlpha : BlendMode.OneMinusSrcAlpha);
            if (material.HasProperty("_Surface")) material.SetFloat("_Surface", 1f);
            if (material.HasProperty("_Blend")) material.SetFloat("_Blend", 0f);
            if (material.HasProperty("_SrcBlend")) material.SetFloat("_SrcBlend", src);
            if (material.HasProperty("_DstBlend")) material.SetFloat("_DstBlend", dst);
            if (material.HasProperty("_SrcBlendAlpha")) material.SetFloat("_SrcBlendAlpha", (float)BlendMode.One);
            if (material.HasProperty("_DstBlendAlpha")) material.SetFloat("_DstBlendAlpha", (float)BlendMode.OneMinusSrcAlpha);
            if (material.HasProperty("_ZWrite")) material.SetFloat("_ZWrite", 1f);
            material.EnableKeyword("_SURFACE_TYPE_TRANSPARENT");
            material.SetOverrideTag("RenderType", "Transparent");
            material.renderQueue = queue;
        }

        /// <summary>Pixels with alpha below one half aren't drawn at all (a helmet's face opening), the rest are solid.</summary>
        public static void MakeCutout(Material material)
        {
            // Gorilla Tag's build has no alpha-test variant of the particle shader, so it's blended (armour pixels are
            // either solid or fully clear, so blending looks the same), and clipped too where the variant exists
            MakeBlended(material, inverted: false, queue: (int)RenderQueue.AlphaTest + 50);
            if (material.HasProperty("_AlphaClip")) material.SetFloat("_AlphaClip", 1f);
            if (material.HasProperty("_Cutoff")) material.SetFloat("_Cutoff", 0.5f);
            material.EnableKeyword("_ALPHATEST_ON");
        }

        public static void FlipVertically(Material material)
        {
            foreach (string name in new[] { "_BaseMap", "_MainTex" })
            {
                if (material.HasProperty(name))
                {
                    material.SetTextureScale(name, new Vector2(1f, -1f));
                    material.SetTextureOffset(name, new Vector2(0f, 1f));
                }
            }
        }

        public static void SetColor(Material material, Color color)
        {
            if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", color);
            if (material.HasProperty("_Color")) material.SetColor("_Color", color);
        }

        public static void SetTexture(Material material, Texture texture)
        {
            if (material.HasProperty("_BaseMap")) material.SetTexture("_BaseMap", texture);
            if (material.HasProperty("_MainTex")) material.SetTexture("_MainTex", texture);
        }
    }
}
