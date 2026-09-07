package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;

import static com.lapissea.dfs.query.Query.Test.fieldLe;
import static com.lapissea.dfs.query.Query.Test.fieldLeEq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-15 (query-fieldle) reproduction.
 *
 * What the code is supposed to do:
 *   {@code Query.Test.fieldLe(ref, needle)} is the STRICT less-than comparison.
 *   It is constructed as {@code new FieldCompare(ref, needle, greater=false,
 *   equal=false)} (Query.java:208-210), while its companion {@code fieldLeEq}
 *   uses {@code equal=true} (Query.java:211-213). The {@code equal} flag is the
 *   only thing separating the two, so {@code fieldLe(needle)} must match only
 *   elements with value < needle and must NOT match an element equal to needle.
 *
 * What it actually does:
 *   {@code FieldCompare.fieldTest} (Query.java:161-165) is:
 *       var res = field.compareTo(check);
 *       if(res == 0 && equal) return true;
 *       return (res>0) == greater;
 *   When the element is EQUAL to the needle (res == 0) and equal == false, the
 *   guard is skipped and the code falls through to {@code (res>0) == greater}.
 *   For strict less-than (greater=false) that evaluates {@code false == false}
 *   -> true. So an equal element MATCHES, making {@code fieldLe(needle)}
 *   behave exactly like {@code fieldLeEq(needle)}.
 *
 * Why this test fails:
 *   On the list [1,2,3,5], {@code fieldLe(num, 2)} should return [1] (only 1 is
 *   strictly less than 2). Because of the fall-through it also returns the
 *   element equal to 2, i.e. [1,2], so the assertion encoding the strict
 *   contract ({@code containsExactly(1)}) fails.
 */
public class ReproQueryFieldLeTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }

	@IOValue
	static class NumberedString extends IOInstance.Managed<NumberedString>{
		private final int    num;
		private final String val;
		public NumberedString(int num, String val){
			this.num = num;
			this.val = val;
		}
		public int num()   { return num; }
		public String val(){ return val; }
	}

	private IOList<NumberedString> makeList() throws IOException{
		IOList<NumberedString> list = Cluster.emptyMem().roots().request(1, IOList.class, NumberedString.class);
		list.addAll(List.of(
			new NumberedString(1, "a"),
			new NumberedString(2, "b"),
			new NumberedString(3, "c"),
			new NumberedString(5, "d")
		));
		return list;
	}

	@Test
	void fieldLeIsStrictLessThan() throws IOException{
		var list = makeList();

		var strictLe = list.where(fieldLe(NumberedString::num, 2))
		                   .mapF(NumberedString::num)
		                   .allToList();

		var leEq = list.where(fieldLeEq(NumberedString::num, 2))
		               .mapF(NumberedString::num)
		               .allToList();

		// Sanity (expected to PASS): fieldLeEq (equal=true) correctly includes
		// the equal element, so num <= 2 -> [1, 2]. This confirms the query
		// harness works and isolates the defect to fieldLe's equal=false path.
		assertThat(leEq).containsExactly(1, 2);

		// BUG: fieldLe is supposed to be STRICT (equal=false) and therefore must
		// exclude the element equal to 2, returning [1]. The fieldTest
		// fall-through (Query.java:161-165) makes it also include 2 -> [1, 2].
		// This assertion encodes the intended strict semantics and fails.
		assertThat(strictLe).containsExactly(1);
	}
}
