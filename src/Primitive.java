/** A ray-intersectable surface carrying one material. */
public interface Primitive {
    /** Nearest hit with t in (tMin, tMax), or null. */
    Hit intersect(Ray ray, double tMin, double tMax);
}
