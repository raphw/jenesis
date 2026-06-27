package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.step.LicenseCheck;
import build.jenesis.step.OsvDownload;
import build.jenesis.step.VulnerabilityCheck;

public class ComplianceModule implements BuildExecutorModule {

    private final OsvDownload osv;
    private final LicenseCheck licenses;
    private final VulnerabilityCheck vulnerabilities;

    public ComplianceModule() {
        this(new OsvDownload(), new LicenseCheck(), new VulnerabilityCheck());
    }

    private ComplianceModule(OsvDownload osv, LicenseCheck licenses, VulnerabilityCheck vulnerabilities) {
        this.osv = osv;
        this.licenses = licenses;
        this.vulnerabilities = vulnerabilities;
    }

    public ComplianceModule osv(OsvDownload osv) {
        return new ComplianceModule(osv, licenses, vulnerabilities);
    }

    public ComplianceModule licenses(LicenseCheck licenses) {
        return new ComplianceModule(osv, licenses, vulnerabilities);
    }

    public ComplianceModule vulnerabilities(VulnerabilityCheck vulnerabilities) {
        return new ComplianceModule(osv, licenses, vulnerabilities);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep("license", licenses, inherited.sequencedKeySet().stream());
        if (osv == null) {
            buildExecutor.addStep("vulnerability", vulnerabilities, inherited.sequencedKeySet().stream());
        } else {
            buildExecutor.addStep("osv", osv, inherited.sequencedKeySet().stream());
            buildExecutor.addStep("vulnerability", vulnerabilities,
                    Stream.concat(inherited.sequencedKeySet().stream(), Stream.of("osv")));
        }
    }
}
