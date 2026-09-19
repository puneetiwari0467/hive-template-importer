import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError, templatesApi } from './lib/api'
import { WORKSPACE_TOKEN_KEY } from './lib/workspace'
import { fixtureTemplate, otherTemplate } from './test/fixtures'

function mockApi() {
  const original = fixtureTemplate()
  return {
    original,
    create: vi.spyOn(templatesApi, 'createWorkspace').mockResolvedValue({ token: 'test-token', workspaceId: 'workspace', templateId: original.id }),
    list: vi.spyOn(templatesApi, 'list').mockResolvedValue({ templates: [original] }),
    get: vi.spyOn(templatesApi, 'get').mockResolvedValue(original),
    update: vi.spyOn(templatesApi, 'update').mockImplementation(async (_token, _id, update) => ({
      ...original,
      version: original.version + 1,
      name: update.name,
      sections: original.sections.map((section) => {
        const changedSection = update.sections.find((candidate) => candidate.id === section.id)
        return {
          ...section,
          name: changedSection?.name ?? section.name,
          items: section.items.map((item) => {
            const changedItem = changedSection?.items.find((candidate) => candidate.id === item.id)
            return {
              ...item,
              name: changedItem?.name ?? item.name,
              comments: item.comments.map((comment) => {
                const changedComment = changedItem?.comments.find((candidate) => candidate.id === comment.id)
                return changedComment ? { ...comment, ...changedComment, previewHtml: changedComment.contentHtml } : comment
              }),
            }
          }),
        }
      }),
    })),
    import: vi.spyOn(templatesApi, 'import').mockResolvedValue({ ...otherTemplate(), name: 'Imported inspection workbook' }),
    duplicate: vi.spyOn(templatesApi, 'duplicate').mockImplementation(async (_token, id, name) => ({ ...otherTemplate(), name, duplicateOf: id })),
  }
}

async function readyApp() {
  render(<App />)
  await screen.findByDisplayValue('Home inspection sample')
}

describe('end-to-end client state with unit-test API mocks', () => {
  beforeEach(() => { localStorage.setItem(WORKSPACE_TOKEN_KEY, 'test-token') })

  it('opens the real API-provided sample on first visit, even with Strict Mode mounting', async () => {
    localStorage.clear()
    const api = mockApi()
    render(<StrictMode><App /></StrictMode>)
    await screen.findByDisplayValue('Home inspection sample')
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(api.get).toHaveBeenCalledWith('test-token', api.original.id)
    expect(screen.getByRole('textbox', { name: 'Section name' })).toHaveValue('Exterior')
    expect(localStorage.length).toBe(1)
    expect(localStorage.getItem(WORKSPACE_TOKEN_KEY)).toBe('test-token')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  it('keeps the entire draft across section navigation and saves every hierarchy branch', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    await readyApp()
    const exactText = '  Changed <strong>wall</strong> text.\nKeep these spaces.  '
    fireEvent.change(screen.getByLabelText('Comment text / HTML'), { target: { value: exactText } })
    await user.click(within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('button', { name: /Roofing/ }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Section name' }), { target: { value: 'Roof systems' } })
    await user.click(within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('button', { name: /Exterior/ }))
    expect(screen.getByLabelText('Comment text / HTML')).toHaveValue(exactText)
    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await screen.findByText('Your changes are saved to this workspace.')
    const update = api.update.mock.calls[0][2]
    expect(update.version).toBe(3)
    expect(update.sections).toHaveLength(2)
    expect(update.sections[0].items[0].comments[0].contentHtml).toBe(exactText)
    expect(update.sections[0].items[0].comments[1].contentHtml).toBe(api.original.sections[0].items[0].comments[1].contentHtml)
    expect(update.sections[1].name).toBe('Roof systems')
    expect(update.sections[1].items[0].id).toBe('item-shingles')
    expect(update.sections[1].items[0].comments[0].id).toBe('comment-roof-defect')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
    const afterSave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterSave)
    expect(afterSave.defaultPrevented).toBe(false)
    expect(localStorage.length).toBe(1)
  })

  it('searches across comment text and sensibly opens a matching section without modifying source', async () => {
    mockApi()
    await readyApp()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search items and comments' }), { target: { value: 'shingles' } })
    expect(screen.getByRole('textbox', { name: 'Section name' })).toHaveValue('Roofing')
    expect(screen.getByText('1 matching items · 1 comments')).toBeInTheDocument()
    expect(screen.getByLabelText('Comment text / HTML')).toHaveValue('Several shingles need review.')
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  it('confirms dirty template switches and keeps edits when the user cancels', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    api.list.mockResolvedValue({ templates: [api.original, otherTemplate()] })
    await readyApp()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'My unsaved title' } })
    await user.click(within(screen.getByRole('navigation', { name: 'Saved templates' })).getByRole('button', { name: /Other inspection template/ }))
    expect(screen.getByRole('dialog', { name: 'Switch templates without saving?' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect(screen.getByRole('textbox', { name: 'Template name' })).toHaveValue('My unsaved title')
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('retains the draft on a conflict and requires confirmation before loading the latest version', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    api.update.mockRejectedValue(new ApiError(409, 'VERSION_CONFLICT', 'Another tab saved this template.'))
    await readyApp()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'Unsaved work' } })
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('A newer version was saved elsewhere')
    expect(screen.getByRole('textbox', { name: 'Template name' })).toHaveValue('Unsaved work')
    await user.click(screen.getByRole('button', { name: 'Reload latest' }))
    expect(screen.getByRole('dialog', { name: 'Reload and discard your draft?' })).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledTimes(1)
    api.get.mockResolvedValue({ ...api.original, version: 4, name: 'Server version' })
    await user.click(screen.getByRole('button', { name: 'Reload latest version' }))
    await screen.findByDisplayValue('Server version')
    expect(api.update).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()
  })

  it('retries a failed template switch, not an unrelated save', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    api.list.mockResolvedValue({ templates: [api.original, otherTemplate()] })
    await readyApp()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'Unsaved work' } })
    api.get.mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', 'API unavailable'))
    await user.click(within(screen.getByRole('navigation', { name: 'Saved templates' })).getByRole('button', { name: /Other inspection template/ }))
    await user.click(screen.getByRole('button', { name: 'Discard & switch' }))
    await screen.findByRole('alert')
    expect(screen.getByRole('textbox', { name: 'Template name' })).toHaveValue('Unsaved work')
    api.get.mockResolvedValue(otherTemplate())
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await user.click(screen.getByRole('button', { name: 'Retry loading' }))
    await screen.findByDisplayValue('Other inspection template')
    expect(api.update).not.toHaveBeenCalled()
  })

  it('shows an unavailable API distinctly and never discards its stored credential', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    api.list.mockRejectedValueOnce(new ApiError(503, 'DATABASE_UNAVAILABLE', 'Database connection unavailable.'))
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('workspace service is unavailable')
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(localStorage.getItem(WORKSPACE_TOKEN_KEY)).toBe('test-token')
    expect(api.create).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await screen.findByDisplayValue('Home inspection sample')
    expect(localStorage.getItem(WORKSPACE_TOKEN_KEY)).toBe('test-token')
  })

  it('rejects a bad selected file before any API request and surfaces real import failures', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    api.import.mockRejectedValue(new ApiError(422, 'INVALID_EXPORT', 'Required column is missing.', ['Expected a Comment column.']))
    await readyApp()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'Kept draft' } })
    await user.click(screen.getByRole('button', { name: 'Import a template' }))
    await user.click(screen.getByRole('button', { name: 'Continue to import' }))
    const picker = screen.getByLabelText('Select Excel workbook')
    fireEvent.change(picker, { target: { files: [new File(['bad'], 'not-a-workbook.txt')] } })
    expect(screen.getByRole('alert')).toHaveTextContent('.xls or .xlsx')
    expect(api.import).not.toHaveBeenCalled()
    fireEvent.change(picker, { target: { files: [new File(['invalid workbook bytes'], 'broken.xlsx')] } })
    await user.click(screen.getByRole('button', { name: 'Import workbook' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Required column is missing.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('textbox', { name: 'Template name' })).toHaveValue('Kept draft')
  })

  it('opens actual imported counts, notes and source review after a successful upload', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    api.import.mockResolvedValue({ ...otherTemplate(), name: 'Imported workbook', importReport: { ...otherTemplate().importReport, sourceRows: 7, importedRows: 3, notes: ['Four source rows were skipped with a documented reason.'] } })
    await readyApp()
    await user.click(screen.getByRole('button', { name: 'Import a template' }))
    await user.upload(screen.getByLabelText('Select Excel workbook'), new File(['sample workbook bytes'], 'sample.xlsx'))
    await user.click(screen.getByRole('button', { name: 'Import workbook' }))
    await screen.findByDisplayValue('Imported workbook')
    expect(screen.getByRole('heading', { name: 'Import review' })).toBeInTheDocument()
    expect(screen.getByText('Four source rows were skipped with a documented reason.')).toBeInTheDocument()
    expect(screen.getByText(/Source and imported row totals differ/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.import).toHaveBeenCalledTimes(1)
  })

  it('creates and selects an independent duplicate without issuing an update to the original', async () => {
    const user = userEvent.setup()
    const api = mockApi()
    await readyApp()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'Unsaved original' } })
    await user.click(screen.getByRole('button', { name: 'Duplicate' }))
    await user.click(screen.getByRole('button', { name: 'Continue to duplicate' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Name your copy' }), { target: { value: '  Independent copy  ' } })
    await user.click(screen.getByRole('button', { name: 'Create independent copy' }))
    await screen.findByDisplayValue('Independent copy')
    expect(api.duplicate).toHaveBeenCalledWith('test-token', api.original.id, 'Independent copy')
    expect(api.update).not.toHaveBeenCalled()
    expect(within(screen.getByRole('navigation', { name: 'Saved templates' })).getByRole('button', { name: /Home inspection sample/ })).toBeInTheDocument()
    expect(api.original.name).toBe('Home inspection sample')
  })

  it('keeps a failed-save draft editable and clears dirty tracking when the user reverts it', async () => {
    const api = mockApi()
    api.update.mockRejectedValue(new ApiError(429, 'DEMO_LIMIT', 'Write limit reached.'))
    await readyApp()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'Quota draft' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Save changes' })) })
    expect(await screen.findByRole('alert')).toHaveTextContent('demo limit has been reached')
    expect(screen.getByRole('textbox', { name: 'Template name' })).toHaveValue('Quota draft')
    expect(screen.getByRole('textbox', { name: 'Template name' })).not.toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Template name' }), { target: { value: 'Home inspection sample' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled())
    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(false)
  })
})
