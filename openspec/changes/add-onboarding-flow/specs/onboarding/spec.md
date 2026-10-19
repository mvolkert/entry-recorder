# Spec Delta

## Purpose

Owns the app's first-run window: which pages a given phone is shown, what each page says and offers, how a
permission's state changes what the user can do, when the window is owed, and how it hands off to the main
UI once it ends.

## ADDED Requirements

### Requirement: The first-run flow is shown exactly when the install owes an answer

The system SHALL show the first-run flow instead of the main UI whenever the install has no recorded
completion answer for it, and SHALL NOT show it once an answer — completion or skip — has been recorded.
No permission outcome, no denial count and no install record shall suppress the flow: an intro the user can
get back into from settings is recoverable, an intro that never appears is not.

#### Scenario: Fresh install
- **WHEN** the app is opened for the first time after install
- **THEN** the flow is shown on its welcome page and the main UI is not reachable until the flow ends

#### Scenario: Answer already recorded
- **WHEN** the app is opened again after the flow was completed or skipped
- **THEN** the main UI opens directly, with no trace of the flow

#### Scenario: Install that updated into this build
- **WHEN** an install that never answered opens the app after an update
- **THEN** the flow is shown rather than seeded as answered, and the pages whose permissions the install
  already holds open as acknowledgements

#### Scenario: Reset from settings
- **WHEN** the user starts the intro again from settings
- **THEN** the recorded answer and both denial counts are forgotten, the flow opens from its first page in
  the same session, and a permission that had stopped offering its prompt can offer it again

### Requirement: The flow presents only the pages the device supports, in a fixed order

The system SHALL present the pages in the order welcome, notifications, microphone, battery exemption,
ready, and SHALL leave out any page whose permission does not exist on the running Android version rather
than showing an ask that cannot be made.

#### Scenario: Android 13 and above
- **WHEN** the flow runs on a device with the runtime notification permission
- **THEN** five pages are shown and the counter reads "Step n of 5"

#### Scenario: Below Android 13
- **WHEN** the flow runs on a device without the runtime notification permission
- **THEN** the notifications page is absent, the remaining four pages stay contiguous, and the counter
  denominator is four — it never names a page that is not there

#### Scenario: Permission already granted before the flow reaches its page
- **WHEN** the flow reaches the page for a permission the device already grants
- **THEN** the page is still shown, as an acknowledgement of the state rather than as an ask

### Requirement: Every page reads centered and stays reachable at large text

The system SHALL render each page's step counter, title and supporting copy centered on the page, including
when the text wraps to more than one line, and SHALL keep the page scrollable so larger system font scales
do not hide content behind the action area.

#### Scenario: Long copy on a narrow phone
- **WHEN** a page's supporting copy wraps across several lines on a compact window
- **THEN** every line of that block is centered on the page rather than left-aligned

#### Scenario: 200 percent font scale
- **WHEN** the system font scale is set to 200 percent
- **THEN** the page scrolls and the flow's actions remain reachable

#### Scenario: Screen reader
- **WHEN** a screen reader walks the page
- **THEN** the page title is announced as a heading and the counter as its preceding label

### Requirement: The flow's actions live in one pinned area that never moves

The system SHALL pin the flow's actions outside the paged content — one filled primary action, one
secondary text action, and the pager's own previous and forward row — always composing all of them, so the
primary action keeps the same size, shape and position on every page and only its label changes.

#### Scenario: Page transition
- **WHEN** the user moves between any two pages of the flow
- **THEN** the filled button occupies the identical frame on screen before and after the move

#### Scenario: Page with no optional action
- **WHEN** a page has nothing to decline
- **THEN** the secondary slot is still present, so the filled button does not shift down or up

#### Scenario: Label change
- **WHEN** a page's action changes from asking to acknowledging
- **THEN** only the label and its leading glyph change; the button's fill, height and width do not

### Requirement: The pager has its own navigation that no permission state can block

The system SHALL offer a previous and a forward control under the flow's action slots, SHALL enable forward
on every page but the last and back on every page but the first whatever each permission's state is, and
SHALL make a press of either move the page only: never a prompt, never a settings screen, never a recorded
answer.

#### Scenario: A declined permission does not stop the tour
- **WHEN** the user has declined a permission twice and presses forward on that page
- **THEN** the flow moves to the next page without opening a prompt or a settings screen

#### Scenario: Forward at the end of the flow
- **WHEN** the user is on the last page
- **THEN** the forward control is disabled and pressing it neither records an answer nor leaves the flow

#### Scenario: Either end of the flow
- **WHEN** the flow is on its first or its last page
- **THEN** both navigation controls are still drawn, the unusable one disabled, so the filled action above
  them keeps the frame it has on every other page

### Requirement: Each ask requests one permission and names that permission

The system SHALL request at most one permission per prompt and SHALL label the action with the specific
permission the resulting prompt will ask for.

#### Scenario: Notifications page
- **WHEN** the user presses the primary action on the notifications page
- **THEN** the system prompt appears for notifications only, and no microphone prompt is fired

#### Scenario: Microphone page
- **WHEN** the user presses the primary action on the microphone page
- **THEN** the system prompt appears for microphone only

### Requirement: A satisfied ask becomes an inert acknowledgement that still lets the user continue

The system SHALL show a granted ask as a disabled acknowledgement — a check and a word such as "Allowed" —
and SHALL carry the way forward in the secondary slot so the page is never a dead end.

#### Scenario: Permission granted from the prompt
- **WHEN** the user grants a permission the flow asked for
- **THEN** the primary action reads as an acknowledgement, cannot be pressed as a control, and the secondary
  action moves the flow to the next page

#### Scenario: Permission granted elsewhere
- **WHEN** the user grants the permission in the phone's own settings and returns to the app
- **THEN** the page shows the acknowledgement without the flow having asked again

### Requirement: An ask the system will no longer show routes to the phone's settings

Once the system stops presenting its prompt for a permission — because the user denied it enough times that
further requests are refused silently — the system SHALL offer the phone's application settings instead of
firing a prompt that would produce no visible result.

#### Scenario: Denied twice
- **WHEN** the user has declined a permission prompt twice and returns to that page
- **THEN** the primary action reads "Open phone settings" and pressing it opens that app's settings page
  rather than a system prompt

#### Scenario: No silent refusal
- **WHEN** the primary action on such a page is pressed
- **THEN** no permission prompt is attempted, so the press is always followed by a visible change

#### Scenario: Granted after visiting settings
- **WHEN** the user grants the permission in settings and returns
- **THEN** the page shows the acknowledgement, not the settings action

### Requirement: Battery exemption is offered through the system's own screen without a new permission

The system SHALL offer the battery-optimization exemption by opening the phone's list of exempt apps, SHALL
NOT request any permission that is not already declared, and SHALL let the user continue past the page
whether or not they took the exemption.

#### Scenario: Exemption not yet granted
- **WHEN** the user presses the primary action on the battery page
- **THEN** the phone's battery-optimization screen opens and the flow is still there on return

#### Scenario: Already exempt
- **WHEN** the flow reaches the battery page on a phone that already exempts the app
- **THEN** the page reads as an acknowledgement and the secondary action continues the flow

#### Scenario: Decline
- **WHEN** the user chooses to leave battery management alone
- **THEN** the flow advances and records the same completion answer it would have recorded otherwise

### Requirement: Ending the flow records one answer and hands off to a matching destination

The system SHALL record exactly one answer when the flow ends, by any route, and SHALL open the main UI on
the destination the user chose.

#### Scenario: Add the first device
- **WHEN** the user finishes the flow by choosing to add an intercom
- **THEN** the main UI opens on the devices tab with its add-device form available

#### Scenario: Browse without a device
- **WHEN** the user finishes the flow without adding a device
- **THEN** the main UI opens on the live view

#### Scenario: Skip from the first page
- **WHEN** the user skips the flow before any ask
- **THEN** an answer is recorded so the flow does not return on the next open, and the monitor service is
  started with whatever permissions the user granted

#### Scenario: Repeated taps
- **WHEN** the finish action is pressed more than once before the hand-off takes effect
- **THEN** still exactly one completion answer is recorded

### Requirement: The flow re-reads the device rather than trusting the answer it was given

The system SHALL re-check permission and exemption state whenever the app returns to the foreground, so the
page a user comes back to reflects the phone's actual state.

#### Scenario: Changed while away
- **WHEN** the user changes a permission in the phone's settings while the app is in the background
- **THEN** the flow shows the state the phone has now, not the state recorded when the user left

#### Scenario: Return from a settings screen the phone does not ship
- **WHEN** an action that opens a phone screen fails because that skin does not provide the screen
- **THEN** the reason is stated on the page itself, and pressing the next page's action clears it

#### Scenario: System back
- **WHEN** the user presses system back on any page but the first
- **THEN** the flow moves back one page and stays open

#### Scenario: System back on the first page
- **WHEN** the user presses system back on the first page
- **THEN** the flow ends the same way its skip action does instead of leaving the app with an unfinished
  onboarding state

### Requirement: The flow survives rotation and re-entry without re-asking

The system SHALL keep the user on the page they were reading across configuration changes, SHALL NOT fire a
prompt the user has already answered, and SHALL show already-granted permissions as acknowledgements when
the flow is opened again from settings.

#### Scenario: Rotate mid-flow
- **WHEN** the user rotates the device on any page
- **THEN** the same page is shown with the same actions and no prompt reappears

#### Scenario: Swipe that does not land
- **WHEN** the user drags a page part-way and lets it return
- **THEN** the actions still belong to the page the user is on, and the page they never reached is not
  counted as visited

#### Scenario: Fling across two pages
- **WHEN** the user flings the pager past two pages in one gesture
- **THEN** the flow settles where the gesture ended rather than being pulled back to a page in between

#### Scenario: Reset after granting everything
- **WHEN** the user resets the intro from settings on a phone that already grants both permissions
- **THEN** each ask page opens as an acknowledgement and no prompt fires unless the user asks for one
  again from that page
