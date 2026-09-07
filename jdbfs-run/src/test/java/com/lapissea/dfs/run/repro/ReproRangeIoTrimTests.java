package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.RangeIO;
import com.lapissea.dfs.io.impl.MemoryData;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.fail;

/**
 * BUG-49: RangeIO.trim() trims the underlying stream at the parent's position instead of trimming the range view.
 *
 * What the code is supposed to do:
 *   RangeIO (jdbfs-core/src/main/java/com/lapissea/dfs/io/RangeIO.java) is a view over the sub-range
 *   [offset, offset+maxLength) of a parent RandomIO. Its trim() override (RangeIO.java:161-165) must
 *   honour the RandomIO.trim() contract (RandomIO.java:331-336): when getPos() >= getSize() the trim
 *   is a no-op and must not modify the underlying stream at all; otherwise the view's size is shrunk
 *   to getPos() (i.e. the parent's size is shrunk to offset + localPos).
 *
 * What it actually does:
 *   1) The guard at RangeIO.java:162-163 computes `parent.remaining() - offset > maxLength` - a value
 *      that depends on the parent's CURRENT CURSOR POSITION, not on the view's own bounds. For a
 *      100-byte parent with view [5,35) (offset=5, maxLength=30) positioned at its end (local pos
 *      30 == size 30, parent pos 35), the guard computes (100-35)-5 = 60 > 30 and throws a spurious
 *      UnsupportedOperationException for a trim that must be a no-op.
 *   2) The body at RangeIO.java:164 unconditionally calls parent.trim(), which truncates the PARENT
 *      at the parent's position. For a 100-byte parent with view [5,55) (offset=5, maxLength=50)
 *      positioned at its end (local pos 50 == size 50, parent pos 55), the no-op trim truncates the
 *      parent from 100 to 55 bytes, silently destroying the 45 bytes that lie beyond the view's window.
 *   The two behaviours are two faces of the same defect: the outcome of view.trim() depends on where
 *   the parent cursor happens to be, not on the view's own state.
 *
 * Why the tests fail:
 *   - noOpTrimAtEndMustNotThrow: the position-dependent guard throws UnsupportedOperationException
 *     even though the view's own state (pos == size) demands a no-op.
 *   - noOpTrimAtEndMustNotTruncateParent: the guard happens to pass (parent cursor near the end),
 *     then parent.trim() truncates the parent at the parent's position -> parent size 55 instead of
 *     100, i.e. the wrong region of the underlying stream is truncated.
 */
public class ReproRangeIoTrimTests{
	
	private static MemoryData parentWithPattern(int len){
		var data = new byte[len];
		for(int i = 0; i<len; i++){
			data[i] = (byte)i;
		}
		return MemoryData.of(data);
	}
	
	@Test
	void noOpTrimAtEndMustNotThrow() throws Exception{
		// Parent: 100 bytes. View window [5, 35): offset=5, maxLength=30 -> view size = min(30, 100-5) = 30.
		var data   = parentWithPattern(100);
		var parent = data.io();
		var view   = RangeIO.of(parent, 5, 30);
		
		// Move the view to its end: local pos 30 == size 30.
		// Per the RandomIO.trim() contract (RandomIO.java:334: `if(pos>=size) return;`) this trim is a no-op.
		view.setPos(100); // RangeIO.setPos clamps to local pos 30 (parent pos becomes 35)
		assertThat(view.getPos()).isEqualTo(30);
		assertThat(view.getSize()).isEqualTo(30);
		
		try{
			view.trim();
		}catch(UnsupportedOperationException e){
			// Bug: RangeIO.java:162-163 computes parent.remaining() - offset = (100-35)-5 = 60 > 30 and
			// throws, although the view's own state (pos == size) demands a no-op.
			fail("no-op trim (view pos == view size) threw UnsupportedOperationException from the " +
			     "position-dependent guard at RangeIO.java:163");
		}
		
		// A no-op trim must leave the underlying stream completely untouched.
		assertThat(data.getIOSize()).as("parent size after a no-op view trim").isEqualTo(100);
	}
	
	@Test
	void noOpTrimAtEndMustNotTruncateParent() throws Exception{
		// Parent: 100 bytes with a known pattern (byte i == i). View window [5, 55): offset=5, maxLength=50
		// -> view size = min(50, 100-5) = 50. The bytes [55, 100) are real data OUTSIDE the view window.
		var data   = parentWithPattern(100);
		var parent = data.io();
		var view   = RangeIO.of(parent, 5, 50);
		
		// Move the view to its end: local pos 50 == size 50 -> trim must be a no-op.
		view.setPos(100); // clamped to local pos 50 (parent pos becomes 55)
		assertThat(view.getPos()).isEqualTo(50);
		assertThat(view.getSize()).isEqualTo(50);
		
		view.trim();
		
		// The view itself is unchanged (its window [5,55) is fully inside the parent).
		assertThat(view.getSize()).as("view size after a no-op trim").isEqualTo(50);
		
		// Bug: RangeIO.java:164 calls parent.trim(), which truncates the parent at the parent's position
		// (55) instead of trimming the view (a no-op here). The 45 bytes in [55,100) - all beyond the view's
		// window - are silently destroyed: the underlying stream shrank from 100 to 55.
		assertThat(data.getIOSize())
		    .as("parent size after a no-op view trim (bytes [55,100) lie outside the view window [5,55))")
		    .isEqualTo(100);
	}
}
