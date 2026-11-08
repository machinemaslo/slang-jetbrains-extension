package slanglsp.discovery;

/** Reads saved build-system metadata; must not configure, install, or build anything. */
interface DiscoveryAdapter {
    void discover(DiscoveryContext context);
}
