#include "beet_event_ring.h"

#include <string.h>

void beet_event_ring_reset(beet_event_ring_state_t *state)
{
    memset(state, 0, sizeof(*state));
    state->next_write_slot = 1U;
}

void beet_event_ring_accept_record(
    beet_event_ring_state_t *state,
    const beet_event_record_t *record)
{
    if (!beet_validate_event_record(record)) {
        return;
    }

    if (!state->has_valid_records || record->seq_no > state->highest_valid_seq_no) {
        state->has_valid_records = true;
        state->highest_valid_seq_no = record->seq_no;
    }
}

void beet_event_ring_finalize(beet_event_ring_state_t *state)
{
    state->next_write_slot = state->has_valid_records ?
        (uint16_t)((state->highest_valid_seq_no + 1U) % BEET_EVENT_RING_CAPACITY) :
        1U;
}

void beet_event_ring_accumulate_summary(
    const beet_event_record_t *record,
    uint16_t *event_count,
    uint32_t pair_totals_s[BEET_PAIR_COUNT])
{
    if (!beet_validate_event_record(record)) {
        return;
    }

    if (record->pair_index >= 1U &&
        record->pair_index <= BEET_PAIR_COUNT &&
        record->trigger_source != BEET_RUN_SOURCE_TEST) {
        pair_totals_s[record->pair_index - 1U] += record->actual_duration_s;
    }
    (*event_count)++;
}
