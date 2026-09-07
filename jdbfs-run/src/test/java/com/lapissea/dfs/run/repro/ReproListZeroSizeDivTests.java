package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.CollectionInfo;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.run.TestInfo;
import com.lapissea.dfs.run.TestUtils;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-20 (list-zerosize-div): ContiguousIOList divides by the element size without a zero guard.
 *
 * <p>What the code is supposed to do:
 * {@code ContiguousIOList.add(index, value)} with {@code index < size()} inserts an element by
 * shifting every element at {@code index} and above one slot forward ({@code forwardDup}), and
 * {@code ContiguousIOList.remove(index)} shifts the elements above {@code index} one slot back
 * ({@code squash}).
 *
 * <p>What it actually does:
 * both {@code forwardDup} (ContiguousIOList.java:628) and {@code squash} (ContiguousIOList.java:730)
 * size their copy buffer as {@code BATCH_BYTES / elementSize} ({@code BATCH_BYTES = 8192},
 * ConfigDefs.java:80) with no check for {@code elementSize == 0}. Zero-size elements are a
 * supported case — {@code addMany} (ContiguousIOList.java:517) explicitly guards with
 * {@code elSiz == 0 ? count : ...} — so a list of elements that serialize to 0 bytes (e.g. the
 * zero-field managed type {@code CollectionInfo.NullValue}) can be built, but any subsequent
 * indexed insertion/removal crashes with {@code ArithmeticException: / by zero} instead of
 * inserting/removing the element.
 *
 * <p>Why the test fails:
 * after building a 3-element list of 0-byte elements (which works thanks to the :517 guard),
 * {@code add(0, ...)} enters {@code forwardDup} and throws {@code ArithmeticException: / by zero}
 * at :628, and {@code remove(0)} enters {@code squash} and throws at :730. Both test methods
 * die with that exception before the "postcondition" assertions are ever reached — that
 * exception IS the reported bug.
 */
public class ReproListZeroSizeDivTests{
	
	@AfterMethod
	public void cleanup(ITestResult method){
		TestUtils.cleanup(method);
	}
	
	private static ContiguousIOList<CollectionInfo.NullValue> buildZeroSizeList(TestInfo info) throws IOException{
		var provider = TestUtils.testChunkProvider(info);
		var chunk    = AllocateTicket.bytes(64).submit(provider);
		return new ContiguousIOList<>(provider, chunk, IOType.of(ContiguousIOList.class, CollectionInfo.NullValue.class));
	}
	
	@Test
	void addIndexOnZeroSizeList() throws IOException{
		var list = buildZeroSizeList(TestInfo.of());
		
		// NullValue is a managed @IOValue type with no serializable fields, so each element
		// occupies exactly 0 bytes — the precondition that makes BATCH_BYTES/elementSize blow up.
		assertThat(StandardStructPipe.sizeOfUnknown(list.getDataProvider(), new CollectionInfo.NullValue(), WordSpace.BYTE))
			.isZero();
		
		// addAll works on 0-byte elements: addMany() carries the elSiz==0 guard (ContiguousIOList.java:517)
		list.addAll(List.of(new CollectionInfo.NullValue(), new CollectionInfo.NullValue(), new CollectionInfo.NullValue()));
		assertThat(list.size()).isEqualTo(3);
		
		// BUG: add(index) with index < size calls forwardDup(), which computes BATCH_BYTES/elementSize
		// at ContiguousIOList.java:628 with no zero guard -> ArithmeticException: / by zero.
		// Any non-zero element size would insert fine here.
		list.add(0, new CollectionInfo.NullValue());
		
		// never reached: the exception above is the reported failure
		assertThat(list.size()).isEqualTo(4);
	}
	
	@Test
	void removeOnZeroSizeList() throws IOException{
		var list = buildZeroSizeList(TestInfo.of());
		
		// same precondition: 0-byte elements are a supported case (addMany guard, ContiguousIOList.java:517)
		assertThat(StandardStructPipe.sizeOfUnknown(list.getDataProvider(), new CollectionInfo.NullValue(), WordSpace.BYTE))
			.isZero();
		
		list.addAll(List.of(new CollectionInfo.NullValue(), new CollectionInfo.NullValue(), new CollectionInfo.NullValue()));
		assertThat(list.size()).isEqualTo(3);
		
		// BUG: remove(index) calls squash(), which computes BATCH_BYTES/elementSize at
		// ContiguousIOList.java:730 with no zero guard -> ArithmeticException: / by zero.
		list.remove(0);
		
		// never reached: the exception above is the reported failure
		assertThat(list.size()).isEqualTo(2);
	}
}
