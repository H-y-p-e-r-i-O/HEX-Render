#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 fragNormal;
in vec3 fragPos;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }

    // Base diffuse lighting
    vec3 baseAlbedo = tex.rgb * vertexColor.rgb * ColorModulator.rgb;
    baseAlbedo = mix(overlayColor.rgb, baseAlbedo, overlayColor.a);
    vec3 litColor = baseAlbedo * lightMapColor.rgb;

    // Specular highlights (Blinn-Phong lobe for metal/plastic weapons and grenades)
    vec3 N = normalize(fragNormal);
    vec3 V = normalize(-fragPos); // Camera is at (0,0,0) in view space

    vec3 H0 = normalize(Light0_Direction + V);
    vec3 H1 = normalize(Light1_Direction + V);

    float NdotH0 = max(0.0, dot(N, H0));
    float NdotH1 = max(0.0, dot(N, H1));

    // Shininess exponent: ~32.0 gives crisp, metallic highlights
    float spec0 = pow(NdotH0, 32.0);
    float spec1 = pow(NdotH1, 32.0);

    float highlight = spec0 * 0.75 + spec1 * 0.35;

    // Modulate specular highlight by lightmap brightness so it doesn't glow in pitch black caves
    float lightLevel = max(lightMapColor.r, max(lightMapColor.g, lightMapColor.b));
    vec3 specularGlint = vec3(1.0, 1.0, 1.0) * highlight * 0.50 * lightLevel;

    vec4 finalColor = vec4(litColor + specularGlint, tex.a * ColorModulator.a);
    fragColor = linear_fog(finalColor, vertexDistance, FogStart, FogEnd, FogColor);
}
