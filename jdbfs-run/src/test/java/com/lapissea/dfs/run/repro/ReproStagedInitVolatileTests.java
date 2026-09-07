package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.StagedInit;
import org.testng.annotations.Test;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-34 stagedinit-volatile: async struct-init data race on non-volatile fields of StagedInit.
 *
 * What the code is supposed to do:
 *   StagedInit (jdbfs-core .../type/StagedInit.java) runs staged struct initialization, possibly on a
 *   separate thread (StagedInit.java:111-134 via Runner.run). Concurrent callers skip the blocking
 *   wait through fast paths that read the progress fields WITHOUT any synchronization:
 *     - waitForStateTimed / waitForStateDone / waitForState: "if (state >= X) return;" (StagedInit.java:169/179/184)
 *     - runOnState: "if (state >= X) onEvent.run();" (StagedInit.java:195)
 *     - actuallyWaitForState: "var info = initInfo; if (info == null) return; // initialized" (StagedInit.java:311-313)
 *   For those unsynchronized loads to be safe, the init thread's final stores (state = STATE_DONE under
 *   stateLock at StagedInit.java:142-146, then the plain "initInfo = null" at StagedInit.java:126) must
 *   be published with a happens-before edge to every reader.
 *
 * What it actually does:
 *   Both fields are plain, NON-volatile instance fields:
 *     - StagedInit.java:62  "private int      state    = STATE_NOT_STARTED;"
 *     - StagedInit.java:63  "private InitInfo initInfo = new InitInfo();"
 *   and the async init thread finishes with a plain, unsynchronized write "initInfo = null"
 *   (StagedInit.java:126; the sync path does the same at StagedInit.java:105). The fast paths above
 *   never take the ReentrantLock (it is only acquired inside the slow wait loop / setInitState).
 *   There is therefore NO happens-before edge between the init thread's final stores and a concurrent
 *   caller's loads: a JMM data race. On weakly ordered architectures (ARM, RISC-V, ...) a caller can
 *   treat the struct as initialized (initInfo == null, or state == STATE_DONE) while the init thread's
 *   stores are not yet visible (or may never be, since the JIT is allowed to cache non-volatile reads),
 *   i.e. the struct is used while init is still in flight. The behavioral race is not reliably
 *   observable on x86-TSO (this machine), so this test is STRUCTURAL: it asserts the missing-volatile
 *   property that makes the fast path a data race.
 *
 * Why the test fails:
 *   Both methods assert the safe property (the fields ARE volatile) and FAIL against the current
 *   production code, where both fields are non-volatile.
 */
public class ReproStagedInitVolatileTests{

	@Test
	public void stateFieldMustBeVolatile() throws NoSuchFieldException{
		var state = StagedInit.class.getDeclaredField("state");
		// key assertion: the fast paths (StagedInit.java:169/179/184/195) read `state` unsynchronized
		// while the async init thread (another thread, StagedInit.java:111-134) writes it; without
		// volatile there is no happens-before -> the bug.
		assertThat(Modifier.isVolatile(state.getModifiers()))
			.as("StagedInit.state (StagedInit.java:62) must be volatile: the unsynchronized fast-path reads "
				+ "(waitForStateTimed/waitForStateDone/waitForState/runOnState) race with the async init "
				+ "thread's stores of the DONE state")
			.isTrue();
	}

	@Test
	public void initInfoFieldMustBeVolatile() throws NoSuchFieldException{
		var info = StagedInit.class.getDeclaredField("initInfo");
		// key assertion: "var info = initInfo; if (info == null) return;" (StagedInit.java:312-313)
		// decides "already initialized" from this field with no lock, while the init thread stores null
		// with a plain unsynchronized write (StagedInit.java:126) -> the bug.
		assertThat(Modifier.isVolatile(info.getModifiers()))
			.as("StagedInit.initInfo (StagedInit.java:63) must be volatile: the 'initInfo == null -> initialized' "
				+ "fast path (StagedInit.java:311-313) has no happens-before with the unsynchronized "
				+ "'initInfo = null' store (StagedInit.java:126)")
			.isTrue();
	}
}
