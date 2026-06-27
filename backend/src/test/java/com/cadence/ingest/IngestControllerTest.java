package com.cadence.ingest;

import com.cadence.common.ApiException;
import com.cadence.event.EventDto;
import com.cadence.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestControllerTest {

    private final IngestService service = mock(IngestService.class);
    private final IngestController controller = new IngestController(service);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static EventDto event() {
        return new EventDto(UUID.randomUUID(), 1, "vscode", null,
                OffsetDateTime.now(), OffsetDateTime.now(), 1000L,
                "app", "t", null, "p", null, false, null);
    }

    private static void authenticate() {
        var p = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), "member");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    @Test
    void rejectsEmptyBatch() {
        ApiException ex = assertThrows(ApiException.class,
                () -> controller.ingest(Collections.emptyList()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void rejectsBatchOverThousand() {
        List<EventDto> big = IntStream.range(0, 1001).mapToObj(i -> event()).toList();
        ApiException ex = assertThrows(ApiException.class, () -> controller.ingest(big));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.status());
    }

    @Test
    void acceptsValidBatchAndDelegates() {
        authenticate();
        when(service.ingest(any(), anyList())).thenReturn(new IngestResult(1, 1, 0));
        IngestResult r = controller.ingest(List.of(event()));
        assertEquals(1, r.stored());
        verify(service).ingest(any(AuthPrincipal.class), anyList());
    }
}
