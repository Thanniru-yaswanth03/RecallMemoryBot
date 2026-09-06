/**
 * Main Controller for RecallMemoryBot Admin Dashboard
 */

let currentGroupPage = 0;
let currentMessagePage = 0;
let currentMemoryPage = 0;
let activityTimer = null;
let searchTimeout = null;

document.addEventListener('DOMContentLoaded', async () => {
    // 1. Authenticate check
    try {
        const me = await AdminApi.getMe();
        if (me && me.username) {
            document.getElementById('operatorName').textContent = me.username;
        }
    } catch (e) {
        window.location.href = '/admin/login.html';
        return;
    }

    // 2. Setup navigation listener
    window.addEventListener('hashchange', handleRoute);
    setupNavListeners();

    // 3. Load initial route
    handleRoute();

    // 4. Populate group dropdown filters
    loadGroupDropdowns();
});

function setupNavListeners() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', (e) => {
            document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
            item.classList.add('active');
        });
    });
}

function handleRoute() {
    const hash = window.location.hash.replace('#', '') || 'dashboard';
    const targetPanel = document.getElementById(`view-${hash}`);

    if (targetPanel) {
        document.querySelectorAll('.view-panel').forEach(p => p.classList.remove('active'));
        targetPanel.classList.add('active');

        document.querySelectorAll('.nav-item').forEach(item => {
            if (item.getAttribute('data-view') === hash) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });

        // Set page title
        const titles = {
            'dashboard': 'System Overview',
            'groups': 'Enrolled Groups',
            'messages': 'Ingested Messages',
            'memories': 'Knowledge & Decisions',
            'activity': 'Operational Activity',
            'system': 'Infrastructure & Diagnostics'
        };
        document.getElementById('pageTitle').textContent = titles[hash] || 'Console';

        // Trigger view-specific loads
        if (hash === 'dashboard') loadDashboard();
        if (hash === 'groups') loadGroups();
        if (hash === 'messages') loadMessages();
        if (hash === 'memories') loadMemories();
        if (hash === 'activity') loadActivity();
        if (hash === 'system') loadSystemDiagnostics();
    }
}

function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('open');
}

/* ---------------- 1. DASHBOARD ---------------- */

async function loadDashboard() {
    try {
        const data = await AdminApi.getDashboard();
        if (!data || !data.stats) return;

        document.getElementById('statGroups').textContent = data.stats.totalGroups.toLocaleString();
        document.getElementById('statActiveGroups').textContent = `${data.stats.activeGroups} active groups`;
        document.getElementById('statUsers').textContent = data.stats.totalUsers.toLocaleString();
        document.getElementById('statMessages').textContent = data.stats.totalMessages.toLocaleString();
        document.getElementById('statMemories').textContent = data.stats.totalMemories.toLocaleString();
        document.getElementById('statEmbeddings').textContent = data.stats.totalEmbeddings.toLocaleString();
        document.getElementById('statPendingEmbeddings').textContent = `${data.stats.pendingEmbeddings} pending vectorization`;

        // Health badges
        const dbBadge = document.getElementById('healthDb');
        dbBadge.textContent = data.health.database;
        dbBadge.className = data.health.database === 'CONNECTED' ? 'badge badge-success' : 'badge badge-danger';

        const pgBadge = document.getElementById('healthPgvector');
        pgBadge.textContent = data.health.pgvector;
        pgBadge.className = data.health.pgvector === 'AVAILABLE' ? 'badge badge-success' : 'badge badge-danger';

        const aiBadge = document.getElementById('healthAi');
        aiBadge.textContent = data.health.aiProvider;
        aiBadge.className = data.health.aiProvider === 'OPERATIONAL' ? 'badge badge-success' : 'badge badge-warning';

        document.getElementById('healthModels').textContent =
            `Chat: ${data.aiConfig.chatModel} | Embed: ${data.aiConfig.embeddingModel} (${data.aiConfig.embeddingDimension}d)`;

        // Load recent activity preview
        loadDashboardActivity();
    } catch (e) {
        console.error('Failed to load dashboard:', e);
    }
}

async function loadDashboardActivity() {
    const tbody = document.getElementById('dashboardActivityTbody');
    try {
        const events = await AdminApi.getActivity(5);
        if (!events || events.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="state-container">No recent activity recorded yet.</td></tr>';
            return;
        }

        tbody.innerHTML = events.map(e => `
            <tr>
                <td>${formatTime(e.timestamp)}</td>
                <td><span class="badge badge-info">${escapeHtml(e.eventType)}</span></td>
                <td>${e.groupId ? 'Group #' + e.groupId : 'Global'}</td>
                <td><span class="badge ${e.status === 'SUCCESS' ? 'badge-success' : 'badge-danger'}">${escapeHtml(e.status)}</span></td>
                <td>${escapeHtml(e.details || '-')}</td>
            </tr>
        `).join('');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="5" class="state-container" style="color: var(--danger)">Failed to load activity.</td></tr>';
    }
}

/* ---------------- 2. GROUPS ---------------- */

async function loadGroups() {
    const tbody = document.getElementById('groupsTbody');
    const search = document.getElementById('groupSearchInput').value.trim();

    try {
        const res = await AdminApi.getGroups(currentGroupPage, 20, search);
        if (!res || !res.content || res.content.length === 0) {
            tbody.innerHTML = '<tr><td colspan="9" class="state-container">No Telegram groups found.</td></tr>';
            document.getElementById('groupPageInfo').textContent = 'Page 1 of 1';
            document.getElementById('groupPrevBtn').disabled = true;
            document.getElementById('groupNextBtn').disabled = true;
            return;
        }

        tbody.innerHTML = res.content.map(g => `
            <tr>
                <td><strong>#${g.id}</strong></td>
                <td><code>${g.telegramChatId}</code></td>
                <td><strong>${escapeHtml(g.title)}</strong></td>
                <td>${g.memberCount}</td>
                <td>${g.messageCount.toLocaleString()}</td>
                <td>${g.memoryCount}</td>
                <td>${formatDate(g.lastActivityAt)}</td>
                <td><span class="badge ${g.isActive ? 'badge-success' : 'badge-warning'}">${g.isActive ? 'ACTIVE' : 'INACTIVE'}</span></td>
                <td><button class="btn-page" onclick="openGroupModal(${g.id})">Inspect</button></td>
            </tr>
        `).join('');

        document.getElementById('groupPageInfo').textContent = `Page ${res.page + 1} of ${Math.max(1, res.totalPages)}`;
        document.getElementById('groupPrevBtn').disabled = res.page <= 0;
        document.getElementById('groupNextBtn').disabled = res.page >= res.totalPages - 1;
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="9" class="state-container" style="color: var(--danger)">Failed to load groups.</td></tr>';
    }
}

function debounceGroupSearch() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
        currentGroupPage = 0;
        loadGroups();
    }, 300);
}

function changeGroupPage(delta) {
    currentGroupPage += delta;
    if (currentGroupPage < 0) currentGroupPage = 0;
    loadGroups();
}

async function openGroupModal(groupId) {
    const modal = document.getElementById('groupDetailModal');
    const titleEl = document.getElementById('modalGroupTitle');
    const bodyEl = document.getElementById('modalGroupBody');

    modal.classList.add('open');
    bodyEl.innerHTML = '<div class="state-container">Loading details...</div>';

    try {
        const data = await AdminApi.getGroupDetail(groupId);
        if (!data || !data.group) {
            bodyEl.innerHTML = '<div class="state-container" style="color: var(--danger)">Group not found.</div>';
            return;
        }

        const g = data.group;
        titleEl.textContent = `${g.title} (Group #${g.id})`;

        bodyEl.innerHTML = `
            <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1rem; margin-bottom: 1.5rem;">
                <div style="background: var(--bg-card); padding: 1rem; border-radius: var(--radius-md);">
                    <div class="stat-label">Telegram Chat ID</div>
                    <div style="font-size: 1.1rem; font-weight: 600; margin-top: 0.25rem;"><code>${g.telegramChatId}</code></div>
                </div>
                <div style="background: var(--bg-card); padding: 1rem; border-radius: var(--radius-md);">
                    <div class="stat-label">Total Messages</div>
                    <div style="font-size: 1.1rem; font-weight: 600; margin-top: 0.25rem;">${g.messageCount.toLocaleString()}</div>
                </div>
                <div style="background: var(--bg-card); padding: 1rem; border-radius: var(--radius-md);">
                    <div class="stat-label">Vector Embeddings</div>
                    <div style="font-size: 1.1rem; font-weight: 600; margin-top: 0.25rem;">${data.embeddingCount.toLocaleString()}</div>
                </div>
                <div style="background: var(--bg-card); padding: 1rem; border-radius: var(--radius-md);">
                    <div class="stat-label">Stored Memories</div>
                    <div style="font-size: 1.1rem; font-weight: 600; margin-top: 0.25rem;">${g.memoryCount}</div>
                </div>
            </div>

            <h4 style="font-size: 0.95rem; margin-bottom: 0.75rem;">Group Members (${data.members.length})</h4>
            <div class="table-responsive" style="margin-bottom: 1.5rem; max-height: 200px; overflow-y: auto;">
                <table class="data-table">
                    <thead><tr><th>User</th><th>Username</th><th>Role</th><th>Joined</th></tr></thead>
                    <tbody>
                        ${data.members.map(m => `
                            <tr>
                                <td>${escapeHtml(m.firstName || 'User')}</td>
                                <td>${m.username ? '@' + escapeHtml(m.username) : '-'}</td>
                                <td><span class="badge ${m.role === 'ADMIN' || m.role === 'CREATOR' ? 'badge-warning' : 'badge-info'}">${escapeHtml(m.role)}</span></td>
                                <td>${formatDate(m.joinedAt)}</td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            </div>

            <h4 style="font-size: 0.95rem; margin-bottom: 0.75rem;">Recent Messages</h4>
            <div class="table-responsive" style="max-height: 220px; overflow-y: auto;">
                <table class="data-table">
                    <thead><tr><th>Msg ID</th><th>Author</th><th>Date</th><th>Preview</th></tr></thead>
                    <tbody>
                        ${data.recentMessages.length === 0 ? '<tr><td colspan="4" class="state-container">No messages yet.</td></tr>' :
                            data.recentMessages.map(msg => `
                                <tr>
                                    <td><code>#${msg.telegramMessageId}</code></td>
                                    <td>${escapeHtml(msg.authorName)}</td>
                                    <td>${formatDate(msg.sentAt)}</td>
                                    <td style="max-width: 320px; text-overflow: ellipsis; overflow: hidden; white-space: nowrap;">${escapeHtml(msg.contentSnippet)}</td>
                                </tr>
                            `).join('')}
                    </tbody>
                </table>
            </div>
        `;
    } catch (e) {
        bodyEl.innerHTML = '<div class="state-container" style="color: var(--danger)">Failed to load group details.</div>';
    }
}

function closeGroupModal() {
    document.getElementById('groupDetailModal').classList.remove('open');
}

/* ---------------- 3. MESSAGES ---------------- */

async function loadMessages() {
    const tbody = document.getElementById('messagesTbody');
    const groupId = document.getElementById('msgGroupFilter').value || null;
    const search = document.getElementById('msgSearchInput').value.trim();

    try {
        const res = await AdminApi.getMessages(currentMessagePage, 20, groupId, null, search);
        if (!res || !res.content || res.content.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="state-container">No messages match criteria.</td></tr>';
            document.getElementById('msgPageInfo').textContent = 'Page 1 of 1';
            document.getElementById('msgPrevBtn').disabled = true;
            document.getElementById('msgNextBtn').disabled = true;
            return;
        }

        tbody.innerHTML = res.content.map(m => `
            <tr>
                <td><code>#${m.telegramMessageId}</code></td>
                <td><strong>${escapeHtml(m.groupTitle)}</strong></td>
                <td>${escapeHtml(m.authorName)} ${m.username ? '<span style="color: var(--text-muted)">(@' + escapeHtml(m.username) + ')</span>' : ''}</td>
                <td><span class="badge badge-info">${escapeHtml(m.messageType)}</span></td>
                <td>${formatDate(m.sentAt)}</td>
                <td><span class="badge ${m.hasEmbedding ? 'badge-success' : 'badge-warning'}">${m.hasEmbedding ? '1536d' : 'NO'}</span></td>
                <td style="max-width: 380px; word-break: break-word;">${escapeHtml(m.contentSnippet)}</td>
            </tr>
        `).join('');

        document.getElementById('msgPageInfo').textContent = `Page ${res.page + 1} of ${Math.max(1, res.totalPages)}`;
        document.getElementById('msgPrevBtn').disabled = res.page <= 0;
        document.getElementById('msgNextBtn').disabled = res.page >= res.totalPages - 1;
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="7" class="state-container" style="color: var(--danger)">Failed to load messages.</td></tr>';
    }
}

function debounceMessageSearch() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
        currentMessagePage = 0;
        loadMessages();
    }, 300);
}

function reloadMessages() {
    currentMessagePage = 0;
    loadMessages();
}

function changeMessagePage(delta) {
    currentMessagePage += delta;
    if (currentMessagePage < 0) currentMessagePage = 0;
    loadMessages();
}

/* ---------------- 4. MEMORIES ---------------- */

async function loadMemories() {
    const tbody = document.getElementById('memoriesTbody');
    const groupId = document.getElementById('memGroupFilter').value || null;
    const type = document.getElementById('memTypeFilter').value || '';
    const search = document.getElementById('memSearchInput').value.trim();

    try {
        const res = await AdminApi.getMemories(currentMemoryPage, 20, groupId, type, search);
        if (!res || !res.content || res.content.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="state-container">No memories match criteria.</td></tr>';
            document.getElementById('memPageInfo').textContent = 'Page 1 of 1';
            document.getElementById('memPrevBtn').disabled = true;
            document.getElementById('memNextBtn').disabled = true;
            return;
        }

        tbody.innerHTML = res.content.map(m => `
            <tr>
                <td><strong>#${m.id}</strong></td>
                <td><strong>${escapeHtml(m.groupTitle)}</strong></td>
                <td><span class="badge badge-warning">${escapeHtml(m.memoryType)}</span></td>
                <td><code>${(m.confidence * 100).toFixed(0)}%</code></td>
                <td>${formatDate(m.createdAt)}</td>
                <td style="max-width: 350px; font-weight: 500;">${escapeHtml(m.content)}</td>
                <td>
                    ${m.sources && m.sources.length > 0 ?
                        m.sources.map(s => `<span class="badge badge-info" title="Msg #${s.telegramMessageId} by ${s.authorName}">Msg #${s.telegramMessageId}</span>`).join(' ') :
                        '<span style="color: var(--text-muted)">Manual</span>'}
                </td>
            </tr>
        `).join('');

        document.getElementById('memPageInfo').textContent = `Page ${res.page + 1} of ${Math.max(1, res.totalPages)}`;
        document.getElementById('memPrevBtn').disabled = res.page <= 0;
        document.getElementById('memNextBtn').disabled = res.page >= res.totalPages - 1;
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="7" class="state-container" style="color: var(--danger)">Failed to load memories.</td></tr>';
    }
}

function debounceMemorySearch() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
        currentMemoryPage = 0;
        loadMemories();
    }, 300);
}

function reloadMemories() {
    currentMemoryPage = 0;
    loadMemories();
}

function changeMemoryPage(delta) {
    currentMemoryPage += delta;
    if (currentMemoryPage < 0) currentMemoryPage = 0;
    loadMemories();
}

/* ---------------- 5. ACTIVITY ---------------- */

async function loadActivity() {
    const tbody = document.getElementById('activityTbody');
    try {
        const events = await AdminApi.getActivity(100);
        if (!events || events.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="state-container">No operational activity recorded yet.</td></tr>';
            return;
        }

        tbody.innerHTML = events.map(e => `
            <tr>
                <td><code>${escapeHtml(e.id)}</code></td>
                <td>${formatTime(e.timestamp)}</td>
                <td><span class="badge badge-info">${escapeHtml(e.eventType)}</span></td>
                <td>${e.groupId ? '#' + e.groupId : 'Global'}</td>
                <td><span class="badge ${e.status === 'SUCCESS' ? 'badge-success' : 'badge-danger'}">${escapeHtml(e.status)}</span></td>
                <td>${e.durationMs ? e.durationMs + 'ms' : '-'}</td>
                <td>${escapeHtml(e.details || '-')}</td>
            </tr>
        `).join('');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="7" class="state-container" style="color: var(--danger)">Failed to load activity.</td></tr>';
    }
}

function toggleActivityRefresh() {
    const enabled = document.getElementById('activityAutoRefresh').checked;
    if (enabled) {
        if (!activityTimer) {
            activityTimer = setInterval(loadActivity, 5000);
        }
    } else {
        if (activityTimer) {
            clearInterval(activityTimer);
            activityTimer = null;
        }
    }
}

/* ---------------- 6. SYSTEM HEALTH ---------------- */

async function loadSystemDiagnostics() {
    try {
        const d = await AdminApi.getSystemHealth();
        if (!d) return;

        document.getElementById('diagJvmVersion').textContent = d.jvmVersion;
        document.getElementById('diagProcessors').textContent = `${d.availableProcessors} cores`;
        document.getElementById('diagThreads').textContent = d.activeThreads;

        const heapMb = (d.heapUsedBytes / (1024 * 1024)).toFixed(1);
        const maxMb = (d.heapMaxBytes / (1024 * 1024)).toFixed(1);
        document.getElementById('diagHeapPercent').textContent = `${d.heapUsedPercentage}% (${heapMb}MB / ${maxMb}MB)`;
        document.getElementById('diagHeapBar').style.width = `${Math.min(100, d.heapUsedPercentage)}%`;

        document.getElementById('diagDbStatus').textContent = d.dbStatus;
        document.getElementById('diagDbActive').textContent = d.dbActiveConnections;
        document.getElementById('diagDbIdle').textContent = d.dbIdleConnections;
        document.getElementById('diagPgvector').textContent = d.pgvectorStatus;

        document.getElementById('diagTotalMsgs').textContent = d.totalMessages.toLocaleString();
        document.getElementById('diagTotalEmbedded').textContent = d.totalEmbedded.toLocaleString();
        document.getElementById('diagCoverage').textContent = `${d.embeddingCoveragePercent}%`;
        document.getElementById('diagUpdatesProcessed').textContent = d.totalUpdatesProcessed.toLocaleString();
    } catch (e) {
        console.error('Failed to load system diagnostics:', e);
    }
}

/* ---------------- HELPERS ---------------- */

async function loadGroupDropdowns() {
    try {
        const res = await AdminApi.getGroups(0, 100);
        if (!res || !res.content) return;

        const msgSelect = document.getElementById('msgGroupFilter');
        const memSelect = document.getElementById('memGroupFilter');

        res.content.forEach(g => {
            const opt1 = document.createElement('option');
            opt1.value = g.id;
            opt1.textContent = `${g.title} (#${g.id})`;
            msgSelect.appendChild(opt1);

            const opt2 = document.createElement('option');
            opt2.value = g.id;
            opt2.textContent = `${g.title} (#${g.id})`;
            memSelect.appendChild(opt2);
        });
    } catch (e) {
        // Silently fail if groups not available yet
    }
}

function formatDate(iso) {
    if (!iso) return '-';
    try {
        const d = new Date(iso);
        return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch (e) {
        return iso;
    }
}

function formatTime(iso) {
    if (!iso) return '-';
    try {
        const d = new Date(iso);
        return d.toLocaleTimeString();
    } catch (e) {
        return iso;
    }
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
