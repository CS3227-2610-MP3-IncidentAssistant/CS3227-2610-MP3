package com.company.incidentdesk.persistence.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.company.incidentdesk.domain.attachment.AttachmentId;
import com.company.incidentdesk.domain.attachment.AttachmentType;
import com.company.incidentdesk.domain.attachment.IncidentAttachment;
import com.company.incidentdesk.domain.audit.AuditAction;
import com.company.incidentdesk.persistence.AuditQuery;
import com.company.incidentdesk.persistence.AuditSortDirection;
import com.company.incidentdesk.support.AttachmentFixture;

class AttachmentPersistenceTest {
    @TempDir Path temporary;

    @Test
    void legacyVideoMetadataSurvivesRestartButCannotBeOpened() throws Exception {
        Path directory = temporary.resolve("store");
        IncidentAttachment video;
        AttachmentId id = new AttachmentId(UUID.randomUUID());
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            video = new IncidentAttachment(id, fixture.incident.id(), AttachmentType.MP4,
                    3, "old-private-video.mp4", AttachmentFixture.NOW);
        }
        Path canonical = directory.resolve("incident-desk.dat");
        LocalApplicationStateCodec codec = new LocalApplicationStateCodec();
        LocalApplicationState state = codec.decode(Files.readAllBytes(canonical));
        byte[] legacy = codec.encode(new LocalApplicationState(state.accounts(), state.credentials(),
                state.incidents(), state.comments(), state.auditEvents(), state.sloTargetVersions(), Map.of(id, video), 1));
        Files.write(canonical, legacy);
        Path blob = Files.write(directory.resolve("attachments/" + video.storageName()), new byte[] {1, 2, 3});
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertEquals(video, fixture.store.attachmentStore().find(id).orElseThrow());
            assertFalse(fixture.service.open(id).isSuccess());
            assertEquals("Video.mp4", fixture.service.list(fixture.incident.id()).value().orElseThrow().getFirst().displayName());
            assertArrayEquals(legacy, Files.readAllBytes(canonical));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(blob));
        }
    }

    @Test
    void firstAttachmentUpgradesToCurrentVersionWithBackupAndAudit() throws Exception {
        Path directory = temporary.resolve("store");
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertTrue(fixture.store.findById(fixture.incident.id()).isPresent());
        }
        LocalApplicationStateCodec codec = new LocalApplicationStateCodec();
        Path canonical = directory.resolve("incident-desk.dat");
        LocalApplicationState current = codec.decode(Files.readAllBytes(canonical));
        byte[] legacy = codec.encode(new LocalApplicationState(current.accounts(), current.credentials(),
                current.incidents(), current.comments(), current.auditEvents(), current.sloTargetVersions(), Map.of(), 1));
        Files.write(canonical, legacy);
        Path source = Files.write(temporary.resolve("image.png"), AttachmentFixture.png(1, 1));
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertArrayEquals(legacy, Files.readAllBytes(canonical));
            assertTrue(fixture.service.add(fixture.incident.id(), source).isSuccess());
            assertArrayEquals(legacy, Files.readAllBytes(directory.resolve("incident-desk.dat.bak")));
            LocalApplicationState migrated = codec.decode(Files.readAllBytes(canonical));
            assertEquals(LocalApplicationStateCodec.SCHEMA_VERSION, migrated.schemaVersion());
            assertEquals(current.accounts(), migrated.accounts());
            assertEquals(current.credentials(), migrated.credentials());
            assertEquals(current.incidents(), migrated.incidents());
            assertEquals(Set.of(AuditAction.ATTACHMENT_ADDED),
                    fixture.store.find(AuditQuery.all(), AuditSortDirection.OLDEST_FIRST).stream()
                            .map(event -> event.action()).collect(java.util.stream.Collectors.toSet()));
        }
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertEquals(1, fixture.service.list(fixture.incident.id()).value().orElseThrow().size());
            LocalApplicationState reopened = codec.decode(Files.readAllBytes(canonical));
            assertEquals(LocalApplicationStateCodec.SCHEMA_VERSION, reopened.schemaVersion());
        }
    }

    @Test
    void restartRemovesOnlyMarkedUncommittedUploads() throws Exception {
        Path directory = temporary.resolve("store");
        AttachmentId abandoned = new AttachmentId(UUID.randomUUID());
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            byte[] bytes = AttachmentFixture.png(1, 1);
            new AttachmentFiles(directory).prepare(new IncidentAttachment(abandoned, fixture.incident.id(),
                    AttachmentType.PNG, bytes.length, "image.png", AttachmentFixture.NOW), bytes);
            Files.writeString(directory.resolve("attachments/unrelated.txt"), "preserve");
        }
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertTrue(fixture.store.attachmentStore().list(fixture.incident.id()).isEmpty());
            assertFalse(Files.exists(directory.resolve("attachments/" + abandoned.value() + ".png")));
            assertFalse(Files.exists(directory.resolve("attachments/.pending-" + abandoned.value())));
            assertEquals("preserve", Files.readString(directory.resolve("attachments/unrelated.txt")));
        }
    }

    @Test
    void restartPreservesCommittedBlobWithUnfinishedCleanup() throws Exception {
        Path directory = temporary.resolve("store");
        Path source = Files.write(temporary.resolve("image.png"), AttachmentFixture.png(1, 1));
        AttachmentId id;
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            id = new AttachmentId(UUID.fromString(fixture.service.add(fixture.incident.id(), source)
                    .value().orElseThrow().id()));
            Files.createFile(directory.resolve("attachments/.pending-" + id.value()));
        }
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertTrue(fixture.service.open(id).isSuccess());
            assertFalse(Files.exists(directory.resolve("attachments/.pending-" + id.value())));
        }
    }

    @Test
    void symlinkSourcesAndStoredBlobsAreRejected() throws Exception {
        Path source = Files.write(temporary.resolve("image.png"), AttachmentFixture.png(1, 1));
        Path link = temporary.resolve("linked.png");
        try {
            Files.createSymbolicLink(link, source);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable on this host");
        }
        Path directory = temporary.resolve("store");
        try (AttachmentFixture fixture = new AttachmentFixture(directory)) {
            assertFalse(fixture.service.add(fixture.incident.id(), link).isSuccess());
            AttachmentId id = new AttachmentId(UUID.fromString(fixture.service.add(fixture.incident.id(), source)
                    .value().orElseThrow().id()));
            Path blob = directory.resolve("attachments/" + id.value() + ".png");
            Files.delete(blob);
            Files.createSymbolicLink(blob, source);
            assertFalse(fixture.service.open(id).isSuccess());
            assertArrayEquals(AttachmentFixture.png(1, 1), Files.readAllBytes(source));
        }
    }
}
