const metrics = [
  { label: 'Open incidents', hint: 'Backend integration pending' },
  { label: 'At-risk SLOs', hint: 'Backend integration pending' },
  { label: 'Pending promotions', hint: 'Backend integration pending' },
  { label: 'Resolved this week', hint: 'Backend integration pending' },
]

export function AdminDashboard() {
  return (
    <main id="main-content" className="content">
      <header className="page-header">
        <div>
          <p className="eyebrow">Administration</p>
          <h1>Company incident overview</h1>
          <p>Monitor service health and prepare to manage company-wide incident operations.</p>
        </div>
        <button className="secondary-button" type="button" onClick={() => window.location.reload()}>
          Refresh status
        </button>
      </header>

      <FoundationNotice />
      <MetricOverview />
      <IncidentEmptyState />
    </main>
  )
}

function FoundationNotice() {
  return (
    <section className="notice" aria-labelledby="foundation-heading">
      <div className="notice__icon" aria-hidden="true">i</div>
      <div>
        <h2 id="foundation-heading">Administration foundation is ready</h2>
        <p>
          This release establishes the responsive React shell, production asset pipeline, and deployment contract.
          Protected incident data stays unavailable until server-managed authentication and PostgreSQL persistence are connected.
        </p>
      </div>
    </section>
  )
}

function MetricOverview() {
  return (
    <section aria-labelledby="metrics-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Live operations</p>
          <h2 id="metrics-heading">At a glance</h2>
        </div>
        <span className="data-state">No protected data loaded</span>
      </div>
      <div className="metric-grid">
        {metrics.map((metric) => (
          <article className="metric-card" key={metric.label}>
            <p>{metric.label}</p>
            <strong aria-label={`${metric.label} unavailable`}>—</strong>
            <small>{metric.hint}</small>
          </article>
        ))}
      </div>
    </section>
  )
}

function IncidentEmptyState() {
  return (
    <section className="incident-panel" aria-labelledby="incidents-heading">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Incident queue</p>
          <h2 id="incidents-heading">Company incidents</h2>
        </div>
      </div>
      <div className="empty-state">
        <div className="empty-state__mark" aria-hidden="true">◎</div>
        <h3>Incident data is not connected yet</h3>
        <p>The secure administrator API will populate this view after its authentication and persistence decisions are approved.</p>
      </div>
    </section>
  )
}
