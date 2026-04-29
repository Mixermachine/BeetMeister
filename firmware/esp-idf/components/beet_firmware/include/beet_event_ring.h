#ifndef BEET_EVENT_RING_H
#define BEET_EVENT_RING_H

#include <stdint.h>

#include "beet_types.h"

void beet_event_ring_reset(beet_event_ring_state_t *state);
void beet_event_ring_accept_record(
    beet_event_ring_state_t *state,
    const beet_event_record_t *record);
void beet_event_ring_finalize(beet_event_ring_state_t *state);
void beet_event_ring_accumulate_summary(
    const beet_event_record_t *record,
    uint16_t *event_count,
    uint32_t pair_totals_s[BEET_PAIR_COUNT]);
void beet_system_event_ring_accept_record(
    beet_event_ring_state_t *state,
    const beet_system_event_record_t *record);
void beet_system_event_ring_finalize(beet_event_ring_state_t *state);

#endif
