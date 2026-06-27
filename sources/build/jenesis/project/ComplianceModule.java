package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.step.LicenseCheck;
import build.jenesis.step.VulnerabilityCheck;

public class ComplianceModule implements BuildExecutorModule {

    private final LicenseCheck licenses;
    private final VulnerabilityCheck vulnerabilities;

    public ComplianceModule() {
        this(new LicenseCheck(), new VulnerabilityCheck());
    }

    private ComplianceModule(LicenseCheck licenses, VulnerabilityCheck vulnerabilities) {
        this.licenses = licenses;
        this.vulnerabilities = vulnerabilities;
    }

    public ComplianceModule licenses(LicenseCheck licenses) {
        return new ComplianceModule(licenses, vulnerabilities);
    }

    public ComplianceModule vulnerabilities(VulnerabilityCheck vulnerabilities) {
        return new ComplianceModule(licenses, vulnerabilities);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep("license", licenses, inherited.sequencedKeySet().stream());
        buildExecutor.addStep("vulnerability", vulnerabilities, inherited.sequencedKeySet().stream());
    }
}
