package org.whispersystems.textsecuregcm.grpc;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.chat.common.IdentityType;
import org.signal.chat.common.ServiceIdentifier;
import org.signal.chat.profile.CredentialType;
import org.signal.chat.profile.GetExpiringProfileKeyCredentialAnonymousRequest;
import org.signal.chat.profile.GetExpiringProfileKeyCredentialRequest;
import org.signal.chat.profile.GetExpiringProfileKeyCredentialResponse;
import org.signal.chat.profile.GetUnversionedProfileAnonymousRequest;
import org.signal.chat.profile.GetUnversionedProfileRequest;
import org.signal.chat.profile.GetUnversionedProfileResponse;
import org.signal.chat.profile.GetVersionedProfileAnonymousRequest;
import org.signal.chat.profile.GetVersionedProfileRequest;
import org.signal.chat.profile.GetVersionedProfileResponse;
import org.signal.chat.profile.ProfileAnonymousGrpc;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.ServerPublicParams;
import org.signal.libsignal.zkgroup.ServerSecretParams;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations;
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredentialResponse;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCommitment;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialRequest;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialRequestContext;
import org.signal.libsignal.zkgroup.profiles.ServerZkProfileOperations;
import org.whispersystems.textsecuregcm.auth.UnidentifiedAccessChecksum;
import org.whispersystems.textsecuregcm.badges.ProfileBadgeConverter;
import org.whispersystems.textsecuregcm.entities.Badge;
import org.whispersystems.textsecuregcm.entities.BadgeSvg;
import org.whispersystems.textsecuregcm.entities.UserCapabilities;
import org.whispersystems.textsecuregcm.identity.AciServiceIdentifier;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.ProfilesManager;
import org.whispersystems.textsecuregcm.storage.VersionedProfile;
import org.whispersystems.textsecuregcm.tests.util.ProfileTestHelper;
import org.whispersystems.textsecuregcm.util.UUIDUtil;
import javax.annotation.Nullable;

public class ProfileAnonymousGrpcServiceTest {
  private Account account;
  private AccountsManager accountsManager;
  private ProfilesManager profilesManager;
  private ProfileBadgeConverter profileBadgeConverter;
  private ProfileAnonymousGrpc.ProfileAnonymousBlockingStub profileAnonymousBlockingStub;
  private ServerZkProfileOperations serverZkProfileOperations;

  @RegisterExtension
  static final GrpcServerExtension GRPC_SERVER_EXTENSION = new GrpcServerExtension();

  @BeforeEach
  void setup() {
    account = mock(Account.class);
    accountsManager = mock(AccountsManager.class);
    profilesManager = mock(ProfilesManager.class);
    profileBadgeConverter = mock(ProfileBadgeConverter.class);
    serverZkProfileOperations = mock(ServerZkProfileOperations.class);

    final Metadata metadata = new Metadata();
    metadata.put(AcceptLanguageInterceptor.ACCEPTABLE_LANGUAGES_GRPC_HEADER, "en-us");
    metadata.put(UserAgentInterceptor.USER_AGENT_GRPC_HEADER, "Signal-Android/1.2.3");

    profileAnonymousBlockingStub = ProfileAnonymousGrpc.newBlockingStub(GRPC_SERVER_EXTENSION.getChannel())
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

    final ProfileAnonymousGrpcService profileAnonymousGrpcService = new ProfileAnonymousGrpcService(
        accountsManager,
        profilesManager,
        profileBadgeConverter,
        serverZkProfileOperations
    );

    GRPC_SERVER_EXTENSION.getServiceRegistry()
        .addService(profileAnonymousGrpcService);
  }

  @Test
  void getUnversionedProfile() {
    final UUID targetUuid = UUID.randomUUID();
    final org.whispersystems.textsecuregcm.identity.ServiceIdentifier serviceIdentifier = new AciServiceIdentifier(targetUuid);

    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);
    final ECKeyPair identityKeyPair = Curve.generateKeyPair();
    final IdentityKey identityKey = new IdentityKey(identityKeyPair.getPublicKey());

    final List<Badge> badges = List.of(new Badge(
        "TEST",
        "other",
        "Test Badge",
        "This badge is in unit tests.",
        List.of("l", "m", "h", "x", "xx", "xxx"),
        "SVG",
        List.of(
            new BadgeSvg("sl", "sd"),
            new BadgeSvg("ml", "md"),
            new BadgeSvg("ll", "ld")))
    );

    when(account.getBadges()).thenReturn(Collections.emptyList());
    when(profileBadgeConverter.convert(any(), any(), anyBoolean())).thenReturn(badges);
    when(account.isUnrestrictedUnidentifiedAccess()).thenReturn(false);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(account.getIdentityKey(org.whispersystems.textsecuregcm.identity.IdentityType.ACI)).thenReturn(identityKey);
    when(accountsManager.getByServiceIdentifierAsync(serviceIdentifier)).thenReturn(CompletableFuture.completedFuture(Optional.of(account)));

    final GetUnversionedProfileAnonymousRequest request = GetUnversionedProfileAnonymousRequest.newBuilder()
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .setRequest(GetUnversionedProfileRequest.newBuilder()
            .setServiceIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(targetUuid)))
                .build())
            .build())
        .build();

    final GetUnversionedProfileResponse response = profileAnonymousBlockingStub.getUnversionedProfile(request);

    final byte[] unidentifiedAccessChecksum = UnidentifiedAccessChecksum.generateFor(unidentifiedAccessKey);
    final GetUnversionedProfileResponse expectedResponse = GetUnversionedProfileResponse.newBuilder()
        .setIdentityKey(ByteString.copyFrom(identityKey.serialize()))
        .setUnidentifiedAccess(ByteString.copyFrom(unidentifiedAccessChecksum))
        .setUnrestrictedUnidentifiedAccess(false)
        .setCapabilities(ProfileGrpcHelper.buildUserCapabilities(UserCapabilities.createForAccount(account)))
        .addAllBadges(ProfileGrpcHelper.buildBadges(badges))
        .build();

    verify(accountsManager).getByServiceIdentifierAsync(serviceIdentifier);
    assertEquals(expectedResponse, response);
  }

  @ParameterizedTest
  @MethodSource
  void getUnversionedProfileUnauthenticated(final IdentityType identityType, final boolean missingUnidentifiedAccessKey, final boolean accountNotFound) {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);

    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(account.isUnrestrictedUnidentifiedAccess()).thenReturn(false);
    when(accountsManager.getByServiceIdentifierAsync(any())).thenReturn(
        CompletableFuture.completedFuture(accountNotFound ? Optional.empty() : Optional.of(account)));

    final GetUnversionedProfileAnonymousRequest.Builder requestBuilder = GetUnversionedProfileAnonymousRequest.newBuilder()
        .setRequest(GetUnversionedProfileRequest.newBuilder()
            .setServiceIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(identityType)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(UUID.randomUUID())))
                .build())
            .build());

    if (!missingUnidentifiedAccessKey) {
      requestBuilder.setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey));
    }

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getUnversionedProfile(requestBuilder.build()));

    assertEquals(Status.UNAUTHENTICATED.getCode(), statusRuntimeException.getStatus().getCode());
  }

  private static Stream<Arguments> getUnversionedProfileUnauthenticated() {
    return Stream.of(
        Arguments.of(IdentityType.IDENTITY_TYPE_PNI, false, false),
        Arguments.of(IdentityType.IDENTITY_TYPE_ACI, true, false),
        Arguments.of(IdentityType.IDENTITY_TYPE_ACI, false, true)
    );
  }

  @ParameterizedTest
  @MethodSource
  void getVersionedProfile(final String requestVersion,
      @Nullable final String accountVersion,
      final boolean expectResponseHasPaymentAddress) {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);

    final VersionedProfile profile = mock(VersionedProfile.class);
    final byte[] name = ProfileTestHelper.generateRandomByteArray(81);
    final byte[] emoji = ProfileTestHelper.generateRandomByteArray(60);
    final byte[] about = ProfileTestHelper.generateRandomByteArray(156);
    final byte[] paymentAddress = ProfileTestHelper.generateRandomByteArray(582);
    final String avatar = "profiles/" + ProfileTestHelper.generateRandomBase64FromByteArray(16);

    when(profile.name()).thenReturn(name);
    when(profile.aboutEmoji()).thenReturn(emoji);
    when(profile.about()).thenReturn(about);
    when(profile.paymentAddress()).thenReturn(paymentAddress);
    when(profile.avatar()).thenReturn(avatar);

    when(account.getCurrentProfileVersion()).thenReturn(Optional.ofNullable(accountVersion));
    when(account.isUnrestrictedUnidentifiedAccess()).thenReturn(false);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));

    when(accountsManager.getByServiceIdentifierAsync(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(account)));
    when(profilesManager.getAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));

    final GetVersionedProfileAnonymousRequest request = GetVersionedProfileAnonymousRequest.newBuilder()
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .setRequest(GetVersionedProfileRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(UUID.randomUUID())))
                .build())
            .setVersion(requestVersion)
            .build())
        .build();

    final GetVersionedProfileResponse response = profileAnonymousBlockingStub.getVersionedProfile(request);

    final GetVersionedProfileResponse.Builder expectedResponseBuilder = GetVersionedProfileResponse.newBuilder()
        .setName(ByteString.copyFrom(name))
        .setAbout(ByteString.copyFrom(about))
        .setAboutEmoji(ByteString.copyFrom(emoji))
        .setAvatar(avatar);

    if (expectResponseHasPaymentAddress) {
      expectedResponseBuilder.setPaymentAddress(ByteString.copyFrom(paymentAddress));
    }

    assertEquals(expectedResponseBuilder.build(), response);
  }

  private static Stream<Arguments> getVersionedProfile() {
    return Stream.of(
        Arguments.of("version1", "version1", true),
        Arguments.of("version1", null, true),
        Arguments.of("version1", "version2", false)
    );
  }

  @Test
  void getVersionedProfileVersionNotFound() {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);

    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(account.isUnrestrictedUnidentifiedAccess()).thenReturn(false);

    when(accountsManager.getByServiceIdentifierAsync(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(account)));
    when(profilesManager.getAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    final GetVersionedProfileAnonymousRequest request = GetVersionedProfileAnonymousRequest.newBuilder()
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .setRequest(GetVersionedProfileRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(UUID.randomUUID())))
                .build())
            .setVersion("someVersion")
            .build())
        .build();

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getVersionedProfile(request));

    assertEquals(Status.NOT_FOUND.getCode(), statusRuntimeException.getStatus().getCode());
  }

  @ParameterizedTest
  @MethodSource
  void getVersionedProfileUnauthenticated(final boolean missingUnidentifiedAccessKey,
      final boolean accountNotFound) {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);

    when(account.isUnrestrictedUnidentifiedAccess()).thenReturn(false);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(accountsManager.getByServiceIdentifierAsync(any())).thenReturn(
        CompletableFuture.completedFuture(accountNotFound ? Optional.empty() : Optional.of(account)));

    final GetVersionedProfileAnonymousRequest.Builder requestBuilder = GetVersionedProfileAnonymousRequest.newBuilder()
        .setRequest(GetVersionedProfileRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(UUID.randomUUID())))
                .build())
            .setVersion("someVersion")
            .build());

    if (!missingUnidentifiedAccessKey) {
      requestBuilder.setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey));
    }

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getVersionedProfile(requestBuilder.build()));

    assertEquals(Status.UNAUTHENTICATED.getCode(), statusRuntimeException.getStatus().getCode());
  }

  private static Stream<Arguments> getVersionedProfileUnauthenticated() {
    return Stream.of(
        Arguments.of(true, false),
        Arguments.of(false, true)
    );
  }
  @Test
  void getVersionedProfilePniInvalidArgument() {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);

    final GetVersionedProfileAnonymousRequest request = GetVersionedProfileAnonymousRequest.newBuilder()
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .setRequest(GetVersionedProfileRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_PNI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(UUID.randomUUID())))
                .build())
            .setVersion("someVersion")
            .build())
        .build();

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getVersionedProfile(request));

    assertEquals(Status.INVALID_ARGUMENT.getCode(), statusRuntimeException.getStatus().getCode());
  }

  @Test
  void getExpiringProfileKeyCredential() throws InvalidInputException, VerificationFailedException {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);
    final UUID targetUuid = UUID.randomUUID();

    final ServerSecretParams serverSecretParams = ServerSecretParams.generate();
    final ServerPublicParams serverPublicParams = serverSecretParams.getPublicParams();

    final ServerZkProfileOperations serverZkProfile = new ServerZkProfileOperations(serverSecretParams);
    final ClientZkProfileOperations clientZkProfile = new ClientZkProfileOperations(serverPublicParams);

    final byte[] profileKeyBytes = new byte[32];
    new SecureRandom().nextBytes(profileKeyBytes);

    final ProfileKey profileKey = new ProfileKey(profileKeyBytes);
    final ProfileKeyCommitment profileKeyCommitment = profileKey.getCommitment(new ServiceId.Aci(targetUuid));
    final ProfileKeyCredentialRequestContext profileKeyCredentialRequestContext =
        clientZkProfile.createProfileKeyCredentialRequestContext(new ServiceId.Aci(targetUuid), profileKey);

    final VersionedProfile profile = mock(VersionedProfile.class);
    when(profile.commitment()).thenReturn(profileKeyCommitment.serialize());

    when(account.getUuid()).thenReturn(targetUuid);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(accountsManager.getByServiceIdentifierAsync(new AciServiceIdentifier(targetUuid))).thenReturn(CompletableFuture.completedFuture(Optional.of(account)));
    when(profilesManager.getAsync(targetUuid, "someVersion")).thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));

    final ProfileKeyCredentialRequest credentialRequest = profileKeyCredentialRequestContext.getRequest();

    final Instant expiration = Instant.now().plus(org.whispersystems.textsecuregcm.util.ProfileHelper.EXPIRING_PROFILE_KEY_CREDENTIAL_EXPIRATION)
        .truncatedTo(ChronoUnit.DAYS);

    final ExpiringProfileKeyCredentialResponse credentialResponse =
        serverZkProfile.issueExpiringProfileKeyCredential(credentialRequest, new ServiceId.Aci(targetUuid), profileKeyCommitment, expiration);

    when(serverZkProfileOperations.issueExpiringProfileKeyCredential(credentialRequest, new ServiceId.Aci(targetUuid), profileKeyCommitment, expiration))
        .thenReturn(credentialResponse);

    final GetExpiringProfileKeyCredentialAnonymousRequest request = GetExpiringProfileKeyCredentialAnonymousRequest.newBuilder()
        .setRequest(GetExpiringProfileKeyCredentialRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(targetUuid)))
                .build())
            .setCredentialRequest(ByteString.copyFrom(credentialRequest.serialize()))
            .setCredentialType(CredentialType.CREDENTIAL_TYPE_EXPIRING_PROFILE_KEY)
            .setVersion("someVersion")
            .build())
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .build();

    final GetExpiringProfileKeyCredentialResponse response = profileAnonymousBlockingStub.getExpiringProfileKeyCredential(request);

    assertArrayEquals(credentialResponse.serialize(), response.getProfileKeyCredential().toByteArray());

    verify(serverZkProfileOperations).issueExpiringProfileKeyCredential(credentialRequest, new ServiceId.Aci(targetUuid), profileKeyCommitment, expiration);

    final ClientZkProfileOperations clientZkProfileCipher = new ClientZkProfileOperations(serverPublicParams);
    assertThatNoException().isThrownBy(() ->
        clientZkProfileCipher.receiveExpiringProfileKeyCredential(profileKeyCredentialRequestContext, new ExpiringProfileKeyCredentialResponse(response.getProfileKeyCredential().toByteArray())));
  }

  @ParameterizedTest
  @MethodSource
  void getExpiringProfileKeyCredentialUnauthenticated(final boolean missingAccount, final boolean missingUnidentifiedAccessKey) {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);
    final UUID targetUuid = UUID.randomUUID();

    when(account.getUuid()).thenReturn(targetUuid);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(accountsManager.getByServiceIdentifierAsync(new AciServiceIdentifier(targetUuid))).thenReturn(
        CompletableFuture.completedFuture(missingAccount ? Optional.empty() : Optional.of(account)));

    final GetExpiringProfileKeyCredentialAnonymousRequest.Builder requestBuilder = GetExpiringProfileKeyCredentialAnonymousRequest.newBuilder()
        .setRequest(GetExpiringProfileKeyCredentialRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(targetUuid)))
                .build())
            .setCredentialRequest(ByteString.copyFrom("credentialRequest".getBytes(StandardCharsets.UTF_8)))
            .setCredentialType(CredentialType.CREDENTIAL_TYPE_EXPIRING_PROFILE_KEY)
            .setVersion("someVersion")
            .build());

    if (!missingUnidentifiedAccessKey) {
      requestBuilder.setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey));
    }

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getExpiringProfileKeyCredential(requestBuilder.build()));

    assertEquals(Status.UNAUTHENTICATED.getCode(), statusRuntimeException.getStatus().getCode());

    verifyNoInteractions(profilesManager);
  }

  private static Stream<Arguments> getExpiringProfileKeyCredentialUnauthenticated() {
    return Stream.of(
        Arguments.of(true, false),
        Arguments.of(false, true)
    );
  }


  @Test
  void getExpiringProfileKeyCredentialProfileNotFound() {
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);
    final UUID targetUuid = UUID.randomUUID();

    when(account.getUuid()).thenReturn(targetUuid);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(accountsManager.getByServiceIdentifierAsync(new AciServiceIdentifier(targetUuid))).thenReturn(
        CompletableFuture.completedFuture(Optional.of(account)));
    when(profilesManager.getAsync(targetUuid, "someVersion")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    final GetExpiringProfileKeyCredentialAnonymousRequest request = GetExpiringProfileKeyCredentialAnonymousRequest.newBuilder()
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .setRequest(GetExpiringProfileKeyCredentialRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(IdentityType.IDENTITY_TYPE_ACI)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(targetUuid)))
                .build())
            .setCredentialRequest(ByteString.copyFrom("credentialRequest".getBytes(StandardCharsets.UTF_8)))
            .setCredentialType(CredentialType.CREDENTIAL_TYPE_EXPIRING_PROFILE_KEY)
            .setVersion("someVersion")
            .build())
        .build();

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getExpiringProfileKeyCredential(request));

    assertEquals(Status.NOT_FOUND.getCode(), statusRuntimeException.getStatus().getCode());
  }

  @ParameterizedTest
  @MethodSource
  void getExpiringProfileKeyCredentialInvalidArgument(final IdentityType identityType, final CredentialType credentialType,
      final boolean throwZkVerificationException) throws VerificationFailedException {
    final UUID targetUuid = UUID.randomUUID();
    final byte[] unidentifiedAccessKey = new byte[16];
    new SecureRandom().nextBytes(unidentifiedAccessKey);

    if (throwZkVerificationException) {
      when(serverZkProfileOperations.issueExpiringProfileKeyCredential(any(), any(), any(), any())).thenThrow(new VerificationFailedException());
    }

    final VersionedProfile profile = mock(VersionedProfile.class);
    when(profile.commitment()).thenReturn("commitment".getBytes(StandardCharsets.UTF_8));
    when(account.getUuid()).thenReturn(targetUuid);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));
    when(accountsManager.getByServiceIdentifierAsync(new AciServiceIdentifier(targetUuid))).thenReturn(CompletableFuture.completedFuture(Optional.of(account)));
    when(profilesManager.getAsync(targetUuid, "someVersion")).thenReturn(CompletableFuture.completedFuture(Optional.of(profile)));

    final GetExpiringProfileKeyCredentialAnonymousRequest request = GetExpiringProfileKeyCredentialAnonymousRequest.newBuilder()
        .setUnidentifiedAccessKey(ByteString.copyFrom(unidentifiedAccessKey))
        .setRequest(GetExpiringProfileKeyCredentialRequest.newBuilder()
            .setAccountIdentifier(ServiceIdentifier.newBuilder()
                .setIdentityType(identityType)
                .setUuid(ByteString.copyFrom(UUIDUtil.toBytes(targetUuid)))
                .build())
            .setCredentialRequest(ByteString.copyFrom("credentialRequest".getBytes(StandardCharsets.UTF_8)))
            .setCredentialType(credentialType)
            .setVersion("someVersion")
            .build())
        .build();

    final StatusRuntimeException statusRuntimeException = assertThrows(StatusRuntimeException.class,
        () -> profileAnonymousBlockingStub.getExpiringProfileKeyCredential(request));

    assertEquals(Status.INVALID_ARGUMENT.getCode(), statusRuntimeException.getStatus().getCode());
  }

  private static Stream<Arguments> getExpiringProfileKeyCredentialInvalidArgument() {
    return Stream.of(
        // Credential type unspecified
        Arguments.of(IdentityType.IDENTITY_TYPE_ACI, CredentialType.CREDENTIAL_TYPE_UNSPECIFIED, false),
        // Illegal identity type
        Arguments.of(IdentityType.IDENTITY_TYPE_PNI, CredentialType.CREDENTIAL_TYPE_EXPIRING_PROFILE_KEY, false),
        // Artificially fails zero knowledge verification
        Arguments.of(IdentityType.IDENTITY_TYPE_ACI, CredentialType.CREDENTIAL_TYPE_EXPIRING_PROFILE_KEY, true)
    );
  }
}
