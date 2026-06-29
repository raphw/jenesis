package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.step.LicenseCheck;
import build.jenesis.step.OsvDownload;
import build.jenesis.step.VulnerabilityCheck;

public class ComplianceModule implements BuildExecutorModule {

    private final Path configuration;
    private final OsvDownload osv;
    private final LicenseCheck licenses;
    private final VulnerabilityCheck vulnerabilities;

    public ComplianceModule(Path configuration) {
        this(configuration, null, null, null);
    }

    private ComplianceModule(Path configuration,
                             OsvDownload osv,
                             LicenseCheck licenses,
                             VulnerabilityCheck vulnerabilities) {
        this.configuration = configuration;
        this.osv = osv;
        this.licenses = licenses;
        this.vulnerabilities = vulnerabilities;
    }

    public ComplianceModule osv(OsvDownload osv) {
        return new ComplianceModule(configuration, osv, licenses, vulnerabilities);
    }

    public ComplianceModule licenses(LicenseCheck licenses) {
        return new ComplianceModule(configuration, osv, licenses, vulnerabilities);
    }

    public ComplianceModule vulnerabilities(VulnerabilityCheck vulnerabilities) {
        return new ComplianceModule(configuration, osv, licenses, vulnerabilities);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        LicenseCheck license = licenses != null ? licenses : LicenseCheck.configured(configuration);
        if (license != null) {
            buildExecutor.addStep("license", license, inherited.sequencedKeySet().stream());
        }
        VulnerabilityCheck vulnerability = vulnerabilities != null
                ? vulnerabilities
                : VulnerabilityCheck.configured(configuration);
        if (vulnerability != null) {
            OsvDownload download = osv != null ? osv : OsvDownload.configured(configuration);
            if (download != null) {
                buildExecutor.addStep("osv", download, inherited.sequencedKeySet().stream());
                buildExecutor.addStep("vulnerability", vulnerability,
                        Stream.concat(inherited.sequencedKeySet().stream(), Stream.of("osv")));
            } else {
                buildExecutor.addStep("vulnerability", vulnerability, inherited.sequencedKeySet().stream());
            }
        }
    }
}
