package org.ovirt.engine.api.restapi.resource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.WebApplicationException;

import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ovirt.engine.api.model.Tag;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.AttachEntityToTagParameters;
import org.ovirt.engine.core.common.businessentities.Tags;
import org.ovirt.engine.core.common.queries.GetTagsByUserIdParameters;
import org.ovirt.engine.core.common.queries.QueryType;
import org.ovirt.engine.core.compat.Guid;

@MockitoSettings(strictness = Strictness.LENIENT)
public class BackendUserTagResourceTest extends AbstractBackendSubResourceTest<Tag, Tags, BackendUserTagResource> {
    private static final Guid USER_ID = GUIDS[0];
    private static final Guid TAG_ID = GUIDS[1];

    public BackendUserTagResourceTest() {
        super(new BackendUserTagResource(USER_ID, TAG_ID.toString()));
    }

    @Test
    public void testRemove() {
        setUpGetTagExpectations(true);
        setUriInfo(
            setUpActionExpectations(
                ActionType.DetachUserFromTag,
                AttachEntityToTagParameters.class,
                new String[] { "TagId", "EntitiesId" },
                new Object[] { TAG_ID, asList(USER_ID) },
                true,
                true
            )
        );
        verifyRemove(resource.remove());
    }

    @Test
    public void testRemoveCantDo() {
        doTestBadRemove(false, true, CANT_DO);
    }

    @Test
    public void testRemoveFailed() {
        doTestBadRemove(true, false, FAILURE);
    }

    private void doTestBadRemove(boolean valid, boolean success, String detail) {
        setUpGetTagExpectations(true);
        setUriInfo(
            setUpActionExpectations(
                ActionType.DetachUserFromTag,
                AttachEntityToTagParameters.class,
                new String[] { "TagId", "EntitiesId" },
                new Object[] { TAG_ID, asList(USER_ID) },
                valid,
                success));

        verifyFault(assertThrows(WebApplicationException.class, resource::remove), detail);
    }

    @Test
    public void testRemoveNonExistant() {
        setUpGetTagExpectations(false);
        verifyNotFoundException(assertThrows(WebApplicationException.class, () -> resource.remove()));
    }

    private void setUpGetTagExpectations(boolean succeed) {
        setUpGetEntityExpectations(
            QueryType.GetTagsByUserId,
            GetTagsByUserIdParameters.class,
            new String[] { "UserId" },
            new Object[] { USER_ID.toString() },
            succeed ? setUpTagsExpectations() : Collections.emptyList()
        );
    }

    private List<Tags> setUpTagsExpectations() {
        List<Tags> tags = new ArrayList<>();
        for (int i = 0; i < GUIDS.length; i++) {
            Tags tag = setUpTagExpectations(GUIDS[i]);
            tags.add(tag);
        }
        return tags;
    }

    private Tags setUpTagExpectations(Guid tagId) {
        Tags tags = mock(Tags.class);
        when(tags.getTagId()).thenReturn(tagId);
        when(tags.getParentId()).thenReturn(USER_ID);
        return tags;
    }
}
