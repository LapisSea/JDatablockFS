package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-32 (colladapter-size)
 *
 * CollectionAdapter.ElementIOImpl.SealedTypeImpl under-estimates the serialized size of a sealed
 * element's id, so the chunk that {@code calcByteSize} allocates is one byte too small whenever the
 * sealed id falls in one of the ranges where unsigned and signed sizing disagree.
 *
 * The mismatch is between two production computations of the *id portion* of a sealed element:
 *
 *   calcByteSize (jdbfs-core/.../type/field/fields/CollectionAdapter.java:119):
 *       return 1 + NumberSize.bySize(id).bytes + pip.calcUnknownSize(...);
 *                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *       The "1" is the size-flag byte and bySize(id) is the UNSIGNED size of the id.
 *
 *   write          (CollectionAdapter.java:135 -> ContentWriter.writeInt4Dynamic,
 *                   jdbfs-core/.../io/content/ContentWriter.java:115):
 *       var siz = NumberSize.bySizeSigned(v);       // SIGNED size
 *       FlagWriter.writeSingle(this, FLAG_INFO, siz);  // the same 1 flag byte
 *       if(siz != VOID) siz.writeIntSigned(this, v);
 *
 * So both sides write a 1-byte flag, but the value bytes come from different sizing functions:
 * calcByteSize reserves bySize(id) (unsigned) bytes while write actually emits bySizeSigned(id)
 * (signed) bytes. For ids in [128..255], [32768..65535] and [8388608..16777215] the signed form is
 * one byte wider, so calcByteSize allocates a chunk that is too small by one byte.
 *
 * This test compares the exact id formula used at CollectionAdapter.java:119 against the real byte
 * count produced by the exact call used at CollectionAdapter.java:135. It expects them to agree for
 * every possible id; they do not, which is the bug.
 */
public class ReproCollAdapterSizeTests{

	@Test
	public void sealedIdAllocationUnderestimatesActualEncoding() throws IOException{
		// id=0 (null marker) plus controls that size identically, then the three problem ranges.
		int[] ids = {0, 1, 100, 128, 255, 32768, 65535, 8388608, 16777215};

		List<String> mismatches = new ArrayList<>();
		for(int id : ids){
			// Id portion that CollectionAdapter.java:119 (calcByteSize) reserves for the sealed id.
			long calcIdBytes = 1 + NumberSize.bySize(id).bytes;
			// Id portion that CollectionAdapter.java:135 (write -> writeInt4Dynamic) actually emits.
			int  actualIdBytes = measureWriteInt4Dynamic(id);

			if(calcIdBytes != actualIdBytes){
				mismatches.add(String.format(
					"id=%d: calcByteSize:119 reserves %d byte(s) [1 + bySize=%s/%d] but writeInt4Dynamic writes %d byte(s) [1 + bySizeSigned=%s/%d]",
					id,
					calcIdBytes, NumberSize.bySize(id), NumberSize.bySize(id).bytes,
					actualIdBytes, NumberSize.bySizeSigned(id), NumberSize.bySizeSigned(id).bytes));
			}
		}

		assertThat(mismatches)
			.as("calcByteSize:119 (1 + NumberSize.bySize(id).bytes) must reserve as many bytes as " +
			    "writeInt4Dynamic (CollectionAdapter.java:135) actually writes for every sealed id; " +
			    "it under-allocates by 1 byte for ids in [128..255], [32768..65535], [8388608..16777215]")
			.isEmpty();
	}

	/**
	 * Measures the exact number of bytes the production {@code write} path emits for a sealed id,
	 * i.e. the length of {@code ContentWriter.writeInt4Dynamic(id)} (CollectionAdapter.java:135).
	 */
	private int measureWriteInt4Dynamic(int id) throws IOException{
		var out = new ContentOutputStream.BA(new byte[64]);
		out.writeInt4Dynamic(id);
		return out.size();
	}
}
