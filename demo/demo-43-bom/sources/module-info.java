/**
 * A modular project whose versions and checksums come from a bill of
 * materials instead of per-dependency {@code @jenesis.pin} tags. The
 * {@code @jenesis.bom} tag imports {@code bom-platform.properties} from the
 * project's BOM location (default: the configuration location, the project
 * root); a repository-hosted BOM is imported the same way by naming its
 * module coordinate instead.
 *
 * @jenesis.release 25
 * @jenesis.bom bom-platform.properties
 */
module demo.bom {
    requires org.slf4j;

    exports sample;
}
