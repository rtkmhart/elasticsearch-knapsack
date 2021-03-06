/*
 * Copyright (C) 2014 Jörg Prante
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xbib.io.archive;

import org.xbib.io.compress.CompressCodecService;
import org.xbib.io.BytesProgressWatcher;

import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;

public class ArchiveService {

    private final static Map<String, ArchiveCodec> codecs = new WeakHashMap<String, ArchiveCodec>();

    private final static ArchiveService instance = new ArchiveService();

    private ArchiveService() {
        ServiceLoader<ArchiveCodec> loader = ServiceLoader.load(ArchiveCodec.class);
        for (ArchiveCodec codec : loader) {
            if (!codecs.containsKey(codec.getName())) {
                codecs.put(codec.getName(), codec);
            }
        }
    }

    public static ArchiveService getInstance() {
        return instance;
    }

    public ArchiveCodec getCodec(String name) {
        if (codecs.containsKey(name)) {
            return codecs.get(name);
        }
        throw new IllegalArgumentException("archive codec for " + name + " not found in " + codecs);
    }

    public static Set<String> getCodecs() {
        return codecs.keySet();
    }

    @SuppressWarnings("unchecked")
    public static <I extends ArchiveInputStream, O extends ArchiveOutputStream> ArchiveSession<I,O> newSession(Path path, BytesProgressWatcher watcher) {
        for (String archiverName : getCodecs()) {
            if (canOpen(archiverName, path)) {
                return codecs.get(archiverName).newSession(watcher);
            }
        }
        throw new IllegalArgumentException("no archive session implementation found for path " + path);
    }

    private static boolean canOpen(String suffix, Path path) {
        String pathStr = path.toString();
        if (pathStr.endsWith("." + suffix.toLowerCase()) || pathStr.endsWith("." + suffix.toUpperCase())) {
            return true;
        }
        Set<String> codecs = CompressCodecService.getCodecs();
        for (String codec : codecs) {
            String s = "." + suffix + "." + codec;
            if (pathStr.endsWith(s) || pathStr.endsWith(s.toLowerCase()) || pathStr.endsWith(s.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

}
