/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.api.internal.tasks;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.api.NonNullApi;
import org.gradle.caching.BuildCacheKey;
import org.gradle.internal.execution.caching.CachingState;
import org.gradle.internal.execution.history.BeforeExecutionState;
import org.gradle.internal.execution.history.InputExecutionState;
import org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsFinishedStep;
import org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsStartedStep;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.operations.trace.CustomOperationTraceSerialization;
import org.gradle.internal.snapshot.DirectorySnapshot;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshotHierarchyVisitor;
import org.gradle.internal.snapshot.SnapshotVisitResult;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * This operation represents the work of analyzing the task's inputs plus the calculating the cache key.
 *
 * <p>
 * These two operations should be captured separately, but for historical reasons we don't yet do that.
 * To reproduce this composite operation we capture across executors by starting an operation
 * in {@link MarkSnapshottingInputsStartedStep} and finished in {@link MarkSnapshottingInputsFinishedStep}.
 * </p>
 */
public class SnapshotTaskInputsBuildOperationResult implements SnapshotTaskInputsBuildOperationType.Result, CustomOperationTraceSerialization {

    @VisibleForTesting
    final CachingState cachingState;

    public SnapshotTaskInputsBuildOperationResult(CachingState cachingState) {
        this.cachingState = cachingState;
    }

    @Override
    public Map<String, byte[]> getInputValueHashesBytes() {
        return getBeforeExecutionState()
            .map(InputExecutionState::getInputProperties)
            .filter(inputValueFingerprints -> !inputValueFingerprints.isEmpty())
            .map(inputValueFingerprints -> inputValueFingerprints.entrySet().stream()
                .collect(toLinkedHashMap(valueSnapshot -> {
                    Hasher hasher = Hashing.newHasher();
                    valueSnapshot.appendToHasher(hasher);
                    return hasher.hash().toByteArray();
                })))
            .orElse(null);
    }

    @NonNullApi
    private static class State implements VisitState, FileSystemSnapshotHierarchyVisitor {

        final InputFilePropertyVisitor visitor;

        Map<String, FileSystemLocationFingerprint> fingerprints;
        String propertyName;
        HashCode propertyHash;
        String propertyNormalizationStrategyIdentifier;
        String name;
        String path;
        HashCode hash;
        int depth;

        public State(InputFilePropertyVisitor visitor) {
            this.visitor = visitor;
        }

        @Override
        public String getPropertyName() {
            return propertyName;
        }

        @Override
        public byte[] getPropertyHashBytes() {
            return propertyHash.toByteArray();
        }

        @Override
        public String getPropertyNormalizationStrategyName() {
            return propertyNormalizationStrategyIdentifier;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public byte[] getHashBytes() {
            return hash.toByteArray();
        }

        @Override
        public void enterDirectory(DirectorySnapshot physicalSnapshot) {
            this.path = physicalSnapshot.getAbsolutePath();
            this.name = physicalSnapshot.getName();
            this.hash = null;

            if (depth++ == 0) {
                visitor.preRoot(this);
            }

            visitor.preDirectory(this);
        }

        @Override
        public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot) {
            String path = snapshot.getAbsolutePath();
            FileSystemLocationFingerprint fingerprint = fingerprints.get(path);

            if (snapshot.getType() == FileType.Directory) {
                if (fingerprint == null) {
                    return SnapshotVisitResult.SKIP_SUBTREE;
                } else {
                    return SnapshotVisitResult.CONTINUE;
                }
            }

            this.path = path;
            this.name = snapshot.getName();

            if (fingerprint == null) {
                return SnapshotVisitResult.CONTINUE;
            }

            this.hash = fingerprint.getNormalizedContentHash();

            boolean isRoot = depth == 0;
            if (isRoot) {
                visitor.preRoot(this);
            }

            visitor.file(this);

            if (isRoot) {
                visitor.postRoot();
            }
            return SnapshotVisitResult.CONTINUE;
        }

        @Override
        public void leaveDirectory(DirectorySnapshot directorySnapshot) {
            visitor.postDirectory();
            if (--depth == 0) {
                visitor.postRoot();
            }
        }
    }

    @Override
    public void visitInputFileProperties(final InputFilePropertyVisitor visitor) {
        getBeforeExecutionState()
            .map(BeforeExecutionState::getInputFileProperties)
            .ifPresent(inputFileProperties -> {
                State state = new State(visitor);
                for (Map.Entry<String, CurrentFileCollectionFingerprint> entry : inputFileProperties.entrySet()) {
                    CurrentFileCollectionFingerprint fingerprint = entry.getValue();

                    state.propertyName = entry.getKey();
                    state.propertyHash = fingerprint.getHash();
                    state.propertyNormalizationStrategyIdentifier = fingerprint.getStrategyIdentifier();
                    state.fingerprints = fingerprint.getFingerprints();

                    visitor.preProperty(state);
                    fingerprint.getSnapshot().accept(state);
                    visitor.postProperty();
                }
            });
    }

    @Override
    public byte[] getClassLoaderHashBytes() {
        return getBeforeExecutionState()
            .map(InputExecutionState::getImplementation)
            .map(ImplementationSnapshot::getClassLoaderHash)
            .map(HashCode::toByteArray)
            .orElse(null);
    }

    @Override
    public List<byte[]> getActionClassLoaderHashesBytes() {
        return getBeforeExecutionState()
            .map(BeforeExecutionState::getAdditionalImplementations)
            .filter(additionalImplementation -> !additionalImplementation.isEmpty())
            .map(additionalImplementations -> additionalImplementations.stream()
                .map(input -> input.getClassLoaderHash() == null ? null : input.getClassLoaderHash().toByteArray())
                .collect(Collectors.toList()))
            .orElse(null);
    }

    @Nullable
    @Override
    public List<String> getActionClassNames() {
        return getBeforeExecutionState()
            .map(BeforeExecutionState::getAdditionalImplementations)
            .filter(additionalImplementations -> !additionalImplementations.isEmpty())
            .map(additionalImplementations -> additionalImplementations.stream()
                .map(ImplementationSnapshot::getTypeName)
                .collect(Collectors.toList())
            )
            .orElse(null);
    }

    @Nullable
    @Override
    public List<String> getOutputPropertyNames() {
        return getBeforeExecutionState()
            .map(BeforeExecutionState::getOutputFileLocationSnapshots)
            .map(ImmutableSortedMap::keySet)
            .filter(outputPropertyNames -> !outputPropertyNames.isEmpty())
            .map(ImmutableSet::asList)
            .orElse(null);
    }

    @Override
    public byte[] getHashBytes() {
        return getKey()
            .map(BuildCacheKey::toByteArray)
            .orElse(null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public Object getCustomOperationTraceSerializableModel() {
        Map<String, Object> model = new TreeMap<>();

        List<byte[]> actionClassLoaderHashesBytes = getActionClassLoaderHashesBytes();
        if (actionClassLoaderHashesBytes != null) {
            List<String> actionClassloaderHashes = getActionClassLoaderHashesBytes().stream()
                .map(hash -> hash == null ? null : HashCode.fromBytes(hash).toString())
                .collect(Collectors.toList());
            model.put("actionClassLoaderHashes", actionClassloaderHashes);
        } else {
            model.put("actionClassLoaderHashes", null);
        }

        model.put("actionClassNames", getActionClassNames());

        byte[] hashBytes = getHashBytes();
        if (hashBytes != null) {
            model.put("hash", HashCode.fromBytes(hashBytes).toString());
        } else {
            model.put("hash", null);
        }

        byte[] classLoaderHashBytes = getClassLoaderHashBytes();
        if (classLoaderHashBytes != null) {
            model.put("classLoaderHash", HashCode.fromBytes(classLoaderHashBytes).toString());
        } else {
            model.put("classLoaderHash", null);
        }


        model.put("inputFileProperties", fileProperties());

        model.put("inputPropertiesLoadedByUnknownClassLoader", getInputPropertiesLoadedByUnknownClassLoader());

        Map<String, byte[]> inputValueHashesBytes = getInputValueHashesBytes();
        if (inputValueHashesBytes != null) {
            Map<String, String> inputValueHashes = inputValueHashesBytes.entrySet().stream()
                .collect(toLinkedHashMap(value -> value == null ? null : HashCode.fromBytes(value).toString()));
            model.put("inputValueHashes", inputValueHashes);
        } else {
            model.put("inputValueHashes", null);
        }

        model.put("outputPropertyNames", getOutputPropertyNames());

        return model;
    }

    private static <K, V, U> Collector<Map.Entry<K, V>, ?, LinkedHashMap<K, U>> toLinkedHashMap(Function<? super V, ? extends U> valueMapper) {
        return Collectors.toMap(
            Map.Entry::getKey,
            entry -> valueMapper.apply(entry.getValue()),
            (a, b) -> b,
            LinkedHashMap::new
        );
    }

    protected Map<String, Object> fileProperties() {
        final Map<String, Object> fileProperties = new TreeMap<>();
        visitInputFileProperties(new InputFilePropertyVisitor() {
            Property property;
            final Deque<DirEntry> dirStack = new ArrayDeque<>();

            class Property {
                private final String hash;
                private final String normalization;
                private final List<Entry> roots = new ArrayList<>();

                public Property(String hash, String normalization) {
                    this.hash = hash;
                    this.normalization = normalization;
                }

                public String getHash() {
                    return hash;
                }

                public String getNormalization() {
                    return normalization;
                }

                public Collection<Entry> getRoots() {
                    return roots;
                }
            }

            abstract class Entry {
                private final String path;

                public Entry(String path) {
                    this.path = path;
                }

                public String getPath() {
                    return path;
                }

            }

            class FileEntry extends Entry {
                private final String hash;

                FileEntry(String path, String hash) {
                    super(path);
                    this.hash = hash;
                }

                public String getHash() {
                    return hash;
                }
            }

            class DirEntry extends Entry {
                private final List<Entry> children = new ArrayList<>();

                DirEntry(String path) {
                    super(path);
                }

                public Collection<Entry> getChildren() {
                    return children;
                }
            }

            @Override
            public void preProperty(VisitState state) {
                property = new Property(HashCode.fromBytes(state.getPropertyHashBytes()).toString(), state.getPropertyNormalizationStrategyName());
                fileProperties.put(state.getPropertyName(), property);
            }

            @Override
            public void preRoot(VisitState state) {

            }

            @Override
            public void preDirectory(VisitState state) {
                boolean isRoot = dirStack.isEmpty();
                DirEntry dir = new DirEntry(isRoot ? state.getPath() : state.getName());
                if (isRoot) {
                    property.roots.add(dir);
                } else {
                    //noinspection ConstantConditions
                    dirStack.peek().children.add(dir);
                }
                dirStack.push(dir);
            }

            @Override
            public void file(VisitState state) {
                boolean isRoot = dirStack.isEmpty();
                FileEntry file = new FileEntry(isRoot ? state.getPath() : state.getName(), HashCode.fromBytes(state.getHashBytes()).toString());
                if (isRoot) {
                    property.roots.add(file);
                } else {
                    //noinspection ConstantConditions
                    dirStack.peek().children.add(file);
                }
            }

            @Override
            public void postDirectory() {
                dirStack.pop();
            }

            @Override
            public void postRoot() {

            }

            @Override
            public void postProperty() {

            }
        });
        return fileProperties;
    }

    private Optional<BeforeExecutionState> getBeforeExecutionState() {
        return cachingState.fold(
            enabled -> Optional.of(enabled.getBeforeExecutionState()),
            CachingState.Disabled::getBeforeExecutionState
        );
    }

    private Optional<BuildCacheKey> getKey() {
        return cachingState.fold(
            enabled -> Optional.of(enabled.getKey()),
            CachingState.Disabled::getKey
        );
    }
}
