package com.github.wikibot.utils;

import java.text.Collator;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public class CategoryTree {
    private final Node root;
    private final Collator collator;

    public CategoryTree(String rootData, int rootMembers, Collator collator) {
        root = new Node(null, rootData, rootMembers);
        this.collator = Objects.requireNonNull(collator);
    }

    public Node getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return root.toString();
    }

    public class Node {
        private final Node parent;
        private final String name;
        private final int members;
        private final SortedSet<Node> children;

        private Node(Node parent, String name, int members) {
            this.parent = parent;
            this.name = Objects.requireNonNull(name);
            this.members = members;
            children = new TreeSet<>(CategoryTree.this.collator);
        }

        public Node getParent() {
            return parent;
        }

        public String getName() {
            return name;
        }

        public int getMembers() {
            return members;
        }

        public Node addChild(String name, int members) {
            var node = new Node(this, name, members);
            children.add(node);
            return node;
        }

        public Optional<Node> getChild(String name) {
            Objects.requireNonNull(name);

            return children.stream()
                .filter(n -> n.name.equals(name))
                .findFirst();
        }

        @Override
        public int hashCode() {
            return Objects.hash(parent, name);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (obj == null) {
                return false;
            }

            var other = (Node)obj;

            if (parent == null || other.parent == null) {
                return false; // can't be both null (two root nodes)
            }

            return parent.equals(other.parent) && name.equals(other.name);
        }

        @Override
        public String toString() {
            return String.format("%s (%d members, %d subcats)", name, members, children.size());
        }
    }
}
