// A CI-only continuation may reuse a frozen source after its workflow checks
// production-file equality. Never dispatch, cancel or rebuild the producer.
module.exports = async ({github, context, core, sourceSha = context.sha}) => {
  if (!/^[0-9a-f]{40}$/.test(sourceSha)) throw new Error('Invalid producer source');
  for (let attempt = 0; attempt < 90; attempt++) {
    let runs;
    try {
      ({data: {workflow_runs: runs}} = await github.rest.actions.listWorkflowRuns({
        ...context.repo, workflow_id: 'paired-functional-test.yml',
        head_sha: sourceSha, branch: context.ref.replace('refs/heads/', ''), per_page: 30
      }));
    } catch (error) {
      if (![429, 500, 502, 503, 504].includes(error.status)) throw error;
      core.warning('Transient producer status: ' + error.status);
      await new Promise(resolve => setTimeout(resolve, 12000)); continue;
    }
    const run = runs.find(value => value.head_sha === sourceSha && value.event === 'push');
    if (run?.status === 'completed') {
      if (run.conclusion !== 'success') throw new Error('Exact producer failed: ' + run.html_url);
      core.exportVariable('BAIZE_PAIRED_RUN', run.id);
      core.exportVariable('BAIZE_PAIRED_NAME', 'BaiZe-functional-test+' + sourceSha.slice(0, 8) + '-Paired-Test');
      core.info('Using exact signed producer: ' + run.html_url); return;
    }
    core.info('Continuing exact producer ' + sourceSha + ': ' + (run?.status || 'not yet listed'));
    await new Promise(resolve => setTimeout(resolve, 12000));
  }
  throw new Error('Exact producer did not finish within the bounded wait');
};
