# Spec Delta

## ADDED Requirements

### Requirement: Mutually exclusive auto-export toggles
The storage settings screen SHALL offer two auto-export switches — raw MKV auto-export and
auto-transcode export — that can never both be enabled: turning one on immediately turns the other
off. Each switch's hint SHALL make its artifact (raw MKV vs. playable H.264 file in the export
folder) and its exclusion of the other mode understandable.

#### Scenario: Enable auto-transcode while raw auto-export is on
- **WHEN** the user turns on the auto-transcode export switch while raw auto-export is enabled
- **THEN** raw auto-export switches off in the same interaction and only auto-transcode remains on

#### Scenario: Enable raw auto-export while auto-transcode is on
- **WHEN** the user turns on the raw auto-export switch while auto-transcode export is enabled
- **THEN** auto-transcode export switches off in the same interaction and only raw auto-export remains on

#### Scenario: Manual transcode toggle stays independent
- **WHEN** the user changes the manual "Transcode to H.264 on Export" switch
- **THEN** neither auto-export switch's state is affected
