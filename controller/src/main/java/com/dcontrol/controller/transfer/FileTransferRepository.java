package com.dcontrol.controller.transfer;

import com.dcontrol.controller.common.crud.ScopedRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileTransferRepository extends ScopedRepository<FileTransfer> {

    @Query("""
            select t from FileTransfer t
            where t.deviceKey = :deviceKey and t.active = true and t.createdAt > :since
            order by t.createdAt desc""")
    List<FileTransfer> findRecentByDevice(@Param("deviceKey") UUID deviceKey, @Param("since") Instant since);

    @Query("select t from FileTransfer t where t.deviceKey = :deviceKey and t.active = true and t.state in :states")
    List<FileTransfer> findByDeviceInStates(@Param("deviceKey") UUID deviceKey,
                                            @Param("states") Collection<TransferState> states);

    @Query("select count(t) from FileTransfer t where t.deviceKey = :deviceKey and t.active = true and t.state in :states")
    long countByDeviceInStates(@Param("deviceKey") UUID deviceKey, @Param("states") Collection<TransferState> states);

    /** Spool bytes reserved by the current account's open transfers. */
    @Query("select coalesce(sum(t.size), 0) from FileTransfer t where t.active = true and t.state in :states")
    long sumSizesInStates(@Param("states") Collection<TransferState> states);

    @Query("select t from FileTransfer t where t.state in :states and t.expiresAt < :now")
    List<FileTransfer> findExpired(@Param("states") Collection<TransferState> states, @Param("now") Instant now);

    @Query("select t.id from FileTransfer t where t.state in :states and t.finishedAt < :before")
    List<UUID> findIdsFinishedBefore(@Param("states") Collection<TransferState> states,
                                     @Param("before") Instant before);

    @Modifying
    @Query("delete from FileTransfer t where t.state in :states and t.finishedAt < :before")
    int deleteFinishedBefore(@Param("states") Collection<TransferState> states, @Param("before") Instant before);
}
