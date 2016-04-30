package jp.skypencil.jenkins.regression;

import static com.google.common.base.Preconditions.checkNotNull;

import javax.annotation.Nonnull;

import hudson.model.User;
import hudson.scm.ChangeLogSet.Entry;

import com.google.common.base.Function;

final class ChangeSetToAuthor implements Function<Entry, User> {

    @Override
    public User apply(@Nonnull Entry from) {
        checkNotNull(from);
        return from.getAuthor();
    }

}
