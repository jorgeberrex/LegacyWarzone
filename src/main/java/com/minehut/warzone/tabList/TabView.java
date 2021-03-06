package com.minehut.warzone.tabList;

import com.google.common.collect.Lists;
import com.minehut.warzone.module.modules.team.TeamModule;
import com.minehut.warzone.tabList.entries.EmptyTabEntry;
import com.minehut.warzone.tabList.entries.SkinTabEntry;
import com.minehut.warzone.tabList.entries.TabEntry;
import com.minehut.warzone.util.PacketUtils;
import com.minehut.warzone.util.Teams;
import net.minecraft.server.v1_10_R1.PacketPlayOutPlayerInfo;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TabView {

    private final Player viewer;
    private TabSlot[] slots = new TabSlot[80];

    private Set<TabEntry> emptyPlayers = new HashSet<>();
    private static Set<TabEntry> hideEntries = new HashSet<>();

    public TabView(Player viewer) {
        this.viewer = viewer;
        setupPlayer();
    }

    public Player getViewer() {
        return viewer;
    }

    public Set<TabEntry> getEmptyPlayers() {
        return emptyPlayers;
    }

    public void destroy(TabEntry entry, int newSlot, boolean update) {
        for (TabSlot slot : slots) slot.removeEntry(entry, newSlot);
        if (update) updateSlots();
    }

    private void updateSlots() {
        for (TabSlot slot : slots) slot.update();
        Set<String> names = new HashSet<>();
        for (TabEntry entry : hideEntries) names.add(entry.getName());
        PacketUtils.sendPacket(viewer, TabList.getTeamPacket(names, 80, 3));
        hideEntries.clear();
    }

    public void hideEntry(TabEntry entry) {
        if (entry instanceof EmptyTabEntry) emptyPlayers.remove(entry);
        hideEntries.add(entry);
    }

    public void setSlot(TabEntry entry, int slot) {
        if (entry instanceof EmptyTabEntry) emptyPlayers.add(entry);
        hideEntries.remove(entry);
        entry.setSlot(viewer, slot);
    }

    private void setupPlayer() {
        PacketPlayOutPlayerInfo listPacket = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER);
        List<String> names = Lists.newArrayList();
        for (TabEntry entry : TabList.getTabEntries()) {
            listPacket.add(entry.getPlayerInfo(viewer, listPacket));
            names.add(entry.getName());
        }
        for (int i = 0; i < 81; i++) {
            if (i == 80) {
                PacketUtils.sendPacket(viewer, TabList.getTeamPacket((String) null, i, 0));
                continue;
            }
            TabEntry fakePlayer = TabList.getFakePlayer(this);
            slots[i] = new TabSlot(this, fakePlayer, i);
            names.remove(fakePlayer.getName());
        }
        PacketUtils.sendPacket(viewer, TabList.getTeamPacket(names, 80, 3));
        PacketUtils.sendPacket(viewer, listPacket);
        for (TabEntry entry : TabList.getTabEntries()) {
            if (entry instanceof SkinTabEntry) {
                ((SkinTabEntry) entry).createSkinParts(viewer);
            }
        }
    }

    public void update() {
        List<TeamModule> prioritized = getPrioritizedTeams(Teams.getTeamByPlayer(viewer).orNull());
        int obsSize = obsRows(prioritized);
        int biggestTeamCol = TabList.columnsPerTeam > 1 ? 18 - obsSize : -1;
        renderObs(20 - obsSize);

        int row = 0, col = 0, currMax = 0;
        for (TeamModule team : prioritized) {
            renderTeam(team, row, col, biggestTeamCol);
            currMax = Math.max(currMax, getBiggestTeamCol(Collections.singleton(team)));
            if ((col += TabList.columnsPerTeam) > 3) {
                col = 0;
                row += currMax;
                currMax = 0;
            }
        }
        updateSlots();
    }

    private static List<TeamModule> getPrioritizedTeams(TeamModule prioritized) {
        List<TeamModule> teams = Teams.getTeams();
        Iterator<TeamModule> teamIterator = teams.iterator();
        while (teamIterator.hasNext()) {
            TeamModule next = teamIterator.next();
            if (next.isObserver() || (prioritized != null && next.equals(prioritized))) teamIterator.remove();
        }
        if (prioritized != null && !prioritized.isObserver()) teams.add(0, prioritized);
        return teams;
    }

    private void renderTeam(TeamModule team, int startRow, int col, int maxRows) {
        boolean hasPlayer = team.contains(viewer);
        updateTabListSlot(TabList.getTeam(team), startRow, col);
        if (hasPlayer) updateTabListSlot(TabList.getPlayer(viewer), startRow + 1, col);
        int row = hasPlayer ? startRow + 2 : startRow + 1;
        int colOffset = 0;
        for (Player render : getSortedPlayerList(team)) {
            if (render.equals(viewer)) continue;
            if (colOffset > TabList.columnsPerTeam) {
                updateTabListSlot(TabList.getPlayer(render), 80, 0);
            } else {
                if (render.equals(viewer)) continue;
                updateTabListSlot(TabList.getPlayer(render), row, col + colOffset);
                if (row++ >= maxRows && maxRows != -1) {
                    row = startRow + 1;
                    colOffset++;
                }
            }
        }
    }

    private void renderObs(int row) {
        TeamModule team = Teams.getTeamById("observers").get();
        boolean hasPlayer = team.contains(viewer);
        int col = hasPlayer ? 1 : 0;
        if (hasPlayer) updateTabListSlot(TabList.getPlayer(viewer), row > 19 ? 80 : row, 0);
        for (Player render : getSortedPlayerList(team)) {
            if (render.equals(viewer)) continue;
            if (row > 19) {
                updateTabListSlot(TabList.getPlayer(render), 80, 0);
            } else {
                updateTabListSlot(TabList.getPlayer(render), row, col);
                if (col++ >= 3) {
                    col = 0;
                    row++;
                }
            }
        }
    }

    private static int getBiggestTeamCol(Collection<TeamModule> teams) {
        int total = 0, biggestTeam = -1, col = 0;
        for (TeamModule team : teams) {
            if (!team.isObserver()) {
                biggestTeam = Math.max(biggestTeam, team.size());
                if (col++ >= 3) {
                    col = 0;
                    total += 2 + ((biggestTeam + (TabList.columnsPerTeam - 1)) / TabList.columnsPerTeam);
                    biggestTeam = -1;
                }
            }
        }
        if (biggestTeam != -1) total += 2 + (biggestTeam + (TabList.columnsPerTeam - 1)) / TabList.columnsPerTeam;
        return total;
    }

    private static int obsRows(Collection<TeamModule> teamOrder) {
        int maxObsRows = 20 - getBiggestTeamCol(teamOrder);
        int obsRows = Math.min((Teams.getTeamById("observers").get().size() + 3 ) / 4, maxObsRows);
        return obsRows <= 0 ? -1 : obsRows;
    }

    private int rowAndCol(int row, int col) {
        return  row + col * 20;
    }

    private void updateTabListSlot(TabEntry entry, int row, int col) {
        int i = rowAndCol(row, col);
        destroy(entry, i, false);
        if (i < 80) slots[i].setNewEntry(entry);
        else hideEntry(entry);
    }

    public static List<Player> getSortedPlayerList(TeamModule team) {
        if (TabList.updateTeam(team)) {
            Collections.sort(team, new Comparator<Player>() {
                @Override
                public int compare(Player player1, Player player2) {
                    UUID uuid1 = player1.getUniqueId();
                    UUID uuid2 = player2.getUniqueId();

                    boolean dev1 = player1.hasPermission("minehut.staff"), dev2 = player2.hasPermission("minehut.staff");
                    if (dev1 ^ dev2) return dev1 ? -1 : 0;

                    return player1.getName().compareTo(player2.getName());
                }
            });
        }
        return team;
    }

}
