/**
 * Surface material. Colours are linear RGB in 0..1.
 * Procedural patterns are evaluated from the hit point, so wood and
 * marble never need textures.
 */
public final class Material {
    public enum Pattern { NONE, WOOD, MARBLE, CHECKER }

    public final Vec3 diffuse;
    public final Vec3 altDiffuse;
    public final Pattern pattern;
    /** Blinn-Phong specular strength (white highlight). */
    public final double specular;
    public final double shininess;
    /** Fresnel base reflectance for recursive reflections. */
    public final double reflectivity;

    public Material(Vec3 diffuse, double specular, double shininess,
                    double reflectivity) {
        this(diffuse, diffuse, Pattern.NONE, specular, shininess, reflectivity);
    }

    public Material(Vec3 diffuse, Vec3 altDiffuse, Pattern pattern,
                    double specular, double shininess, double reflectivity) {
        this.diffuse = diffuse;
        this.altDiffuse = altDiffuse;
        this.pattern = pattern;
        this.specular = specular;
        this.shininess = shininess;
        this.reflectivity = reflectivity;
    }

    public static Material matte(Vec3 diffuse) {
        return new Material(diffuse, 0.15, 24, 0.0);
    }

    public static Material polished(Vec3 diffuse, double reflectivity) {
        return new Material(diffuse, 0.7, 90, reflectivity);
    }

    public static Material wood(Vec3 lightTone, Vec3 darkTone) {
        return new Material(lightTone, darkTone, Pattern.WOOD, 0.45, 60, 0.25);
    }

    public static Material marble(Vec3 lightTone, Vec3 veinTone) {
        return new Material(lightTone, veinTone, Pattern.MARBLE, 0.5, 70, 0.3);
    }

    /** Diffuse colour at a surface point, with the pattern applied. */
    public Vec3 diffuseAt(Vec3 p) {
        switch (pattern) {
            case WOOD: {
                double grain = Math.sin((p.x * 1.1 + p.z * 0.7) * 2.0
                        + 1.6 * Math.sin(p.z * 2.5 + p.x * 1.5));
                double rings = Math.sin((p.x + p.z) * 3.5 + grain * 2.0);
                double t = 0.5 + 0.5 * rings * grain;
                return diffuse.scale(1 - t * 0.55).add(altDiffuse.scale(t * 0.55));
            }
            case MARBLE: {
                double v = Math.sin(p.x * 3.1 + p.y * 2.3 + p.z * 2.7
                        + 2.0 * Math.sin(p.x * 5.0 + p.z * 4.0));
                double t = Math.pow(Math.abs(v), 6.0);
                return diffuse.scale(1 - t * 0.6).add(altDiffuse.scale(t * 0.6));
            }
            case CHECKER: {
                int cx = (int) Math.floor(p.x + 4.0);
                int cz = (int) Math.floor(p.z + 4.0);
                return ((cx + cz) % 2 == 0) ? diffuse : altDiffuse;
            }
            default:
                return diffuse;
        }
    }
}
