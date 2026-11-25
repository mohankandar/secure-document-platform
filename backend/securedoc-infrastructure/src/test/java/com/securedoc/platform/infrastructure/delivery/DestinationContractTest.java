package com.securedoc.platform.infrastructure.delivery;

import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileValidator;
import com.documentplatform.integration.testkit.ProfileContract;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0087: the contract kit in the build. The repository's own made-up profile always; a destination's published
 * profile and specification when the installation names them ({@code LEXAVAULT_DESTINATION_PROFILE},
 * {@code LEXAVAULT_DESTINATION_OPENAPI}; {@code scripts/Test-LexaVault-DestinationContract.sh} takes them from a release
 * tag and checks their checksums). They are another owner's files and are not kept here.
 */
class DestinationContractTest {

    @Test
    void theMadeUpProfileMatchesItsSpecification() throws Exception {
        check(text("/destination/records-archive-v1.profile.json"), text("/destination/records-archive-v1.openapi.json"));
    }

    @Test
    void aProfileThatDoesNotMatchIsFound() throws Exception {
        String profile = text("/destination/records-archive-v1.profile.json").replace("/v1/deliveries\"", "/v2/deliveries\"");
        IntegrationProfile p = ProfileValidator.validate(profile).profile();
        assertFalse(ProfileContract.checkAgainstSpec(p, OpenApiSpec.parse(text("/destination/records-archive-v1.openapi.json"))).isEmpty());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEXAVAULT_DESTINATION_PROFILE", matches = ".+")
    void thePublishedProfileIsValidMatchesItsSpecificationAndIsOneLexaVaultCanUse() throws Exception {
        Path profile = Path.of(System.getenv("LEXAVAULT_DESTINATION_PROFILE"));
        check(Files.readString(profile), Files.readString(Path.of(System.getenv("LEXAVAULT_DESTINATION_OPENAPI"))));
        var listed = new DestinationProfiles(profile.getParent().toString(),
                new com.documentplatform.outbound.Installation(false, false, false, null, null), (t, r) -> {
                    throw new IllegalArgumentException();
                }, t -> java.util.Set.of()).list().stream().filter(p -> p.file().equals(profile.getFileName().toString())).findFirst().orElseThrow();
        assertEquals(List.of(), listed.problems());
        assertTrue(listed.carriesLevel(), "documents go out with their sensitivity level");
    }

    private static void check(String profile, String openApi) {
        ProfileValidator.Result r = ProfileValidator.validate(profile);
        assertEquals(List.of(), r.problems());
        assertEquals(List.of(), ProfileContract.checkAgainstSpec(r.profile(), OpenApiSpec.parse(openApi)));
    }

    private static String text(String resource) throws Exception {
        try (InputStream in = DestinationContractTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
