# The same deterministic execution profile, with persistence explicitly
# optional so not-applicable is a passing classification rather than a failure.
profile_version=1
core.id=corelab.dummy.no-persistence
core.api_version=1
content.mode=synthetic
content.seed=424242
content.bytes=32
expect.fixture_sha256=90acf7bacbfa9f100fd56a1a72948110ba7fbc43d72f4dfa94ac51d06ac0f08c
execution.frames=12
execution.max_video_width=64
execution.max_video_height=64
input.trace=0,1,1,0,2,0,3,0,1,0,2,0
input.exhaustion=fail
thread.contract=required
persistence.native_save=optional
persistence.save_state=optional
expect.video_sha256=4013b2766949239c4b288ad48f3654dbba4b5ede94bb22bdbdd16b3ef4a55033
expect.audio_sha256=cfdd23f9bb7a077732c04205480fe4d44324f69f45cc60f45802dd0a867867e4
