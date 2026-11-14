# Aura roadmap / deferred ideas

Pins for things deliberately *not* built yet. Kept here so they don't get re-litigated
every batch.

## Volume: Home Assistant ↔ AVR bridge (pinned, not planned)

Aura ignores Jellyfin volume commands on purpose (item 1 in `PLAN-next-batch.md`): the
bitstream must stay at unity gain and the AVR owns the level. The consequence is that
the Jellyfin web slider and Home Assistant's `media_player` volume do nothing, which is
confusing in HA.

The eventual fix is external to Aura: HA talks to the receiver (Denon/Marantz/Yamaha
integration), and either

- HA's Jellyfin entity is used only for transport (play/pause/next) while volume lives on
  the AVR entity, or
- a small HA script reads the AVR's volume and reports it back into the Jellyfin session
  so sliders show the truth. Jellyfin 12 has no client-facing endpoint to set a session's
  reported volume, so this needs a custom capability reply — investigate only if the
  annoyance is real.

Not implemented. Revisit after the current batch.

## Other deferred items

Now planned (not yet built) in `PLAN-wave-3.md`, Part C:

- Quick-connect login.
- Library-selection settings (pick which Jellyfin libraries Aura shows, and keep hidden
  ones out of the cache entirely).
- Changing the server / re-signing in from Settings.
- Queue and playlist reordering (menu-driven up/down/top/bottom, not pointer drag).

Still deferred, out of the wave-3 plan:

- Multi-format / sink validation matrix (Atmos vs. stereo downmix per device).
- Server-reported library scan time in Settings beyond the admin-only
  `/ScheduledTasks` probe (see plan item 2) — would need a non-admin source.
