/*
 *  21.04.2004 Original verion. davagin@udm.ru.
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
/* Modified 2026-11-22: isolated Android local decode, bounded inputs and cancellation; GPL-2.0-or-later. */


package io.github.onlinemo.meloxwatch.data.codec.jmac.tools;

import java.io.IOException;

/** Input-only bridge supplied by the app. It cannot resolve a URL, write a file or load a library. */
public abstract class File {
    public abstract int read(byte[] bytes, int offset, int count) throws IOException;
    public abstract void seek(long position) throws IOException;
    public abstract void checkCancellation() throws IOException;
}
