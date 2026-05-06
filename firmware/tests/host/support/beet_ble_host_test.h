#ifndef BEET_BLE_HOST_TEST_H
#define BEET_BLE_HOST_TEST_H

#include <stdbool.h>
#include <stdint.h>

#include "beet_ble.h"
#include "beet_iface.h"
#include "ble_test_shim.h"

void beet_ble_host_test_reset(void);
void beet_ble_host_test_set_session(
    bool connected,
    bool bonded,
    bool subscribed,
    uint16_t conn_handle,
    uint16_t command_result_handle);
void beet_ble_host_test_set_pending_result(const beet_iface_command_response_t *response);
void beet_ble_host_test_notify_tx(int status);
void beet_ble_host_test_disconnect(void);
void beet_ble_host_test_set_command_result_subscription(bool subscribed);
bool beet_ble_host_test_result_active(void);
bool beet_ble_host_test_result_in_flight(void);
uint16_t beet_ble_host_test_result_chunk_index(void);
uint16_t beet_ble_host_test_result_chunk_count(void);

#endif
