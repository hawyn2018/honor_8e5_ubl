package net.hawyn.hnubl.module;

/** Mapping provenance is per assignment, not a sticky flag from the first discovery. */
final class MappingGeneration {
    static final class Selection {
        final long generation;
        final int assignment;
        final String source;
        final boolean cached;
        Selection(long generation, int assignment, String source) {
            this.generation = generation; this.assignment = assignment; this.source = source;
            this.cached = !"fresh_discovery".equals(source);
        }
    }

    private long generation;
    private int assignments;
    private boolean diskOrigin;

    synchronized void publish(boolean fromDiskCache) {
        generation++;
        assignments = 0;
        diskOrigin = fromDiskCache;
    }

    synchronized Selection assign() {
        if (generation == 0) throw new IllegalStateException("Mapping generation has not been published");
        String source = assignments == 0 ? diskOrigin ? "disk_cache" : "fresh_discovery" : "memory_cache";
        return new Selection(generation, ++assignments, source);
    }

    synchronized long generation() { return generation; }
    synchronized int assignments() { return assignments; }
}
