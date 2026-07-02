/*
 * Copyright © 2026 Howard Hughes Medical Institute
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of HHMI nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.flythrough.imported;

import ij.process.FloatProcessor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * The two static helpers from {@code org.janelia.saalfeldlab.hotknife.util.Util}
 * that {@link ImageJStackOp} needs, extracted so this project does not depend on
 * the full hot-knife {@code Util} class.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public final class StackOpUtil {

	private StackOpUtil() {}

	public static <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public static FloatProcessor materialize(final RandomAccessibleInterval<FloatType> source) {

		final FloatProcessor target = new FloatProcessor((int) source.dimension(0), (int) source.dimension(1));
		copy(
				Views.zeroMin(source),
				ArrayImgs.floats(
						(float[]) target.getPixels(),
						target.getWidth(),
						target.getHeight()));
		return target;
	}
}
